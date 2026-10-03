package assistente.pri;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import assistente.ia.ConsultaIA;
import assistente.ia.LlamaCliManager; // Import atualizado para o CLI Manager
import assistente.ia.PesquisaWikipedia;
import assistente.ui.JanelaFlutuante;
import assistente.voz.MicrofoneInteligente;
import org.opencv.core.*;
import org.opencv.highgui.HighGui;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.awt.Desktop;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static assistente.voz.PiperTTS.estaFalando;
import static assistente.voz.PiperTTS.st_falar;

import javax.sound.sampled.Mixer;

public class AssistenteComVisao {

    static class PersonRecord {

        String name;
        float[] embedding;

        public PersonRecord(String name, float[] embedding) {
            this.name = name;
            this.embedding = embedding;
        }
    }

    static class DetectedFace {

        Rect rect;
        String name;
        double similarity;
        Scalar color;

        public DetectedFace(Rect rect, String name, double similarity, Scalar color) {
            this.rect = rect;
            this.name = name;
            this.similarity = similarity;
            this.color = color;
        }
    }

    private static final List<PersonRecord> knownPersons = new CopyOnWriteArrayList<>();
    private static volatile List<DetectedFace> currentFaces = new CopyOnWriteArrayList<>();
    private static final double SIMILARITY_THRESHOLD = 0.50;

    private static volatile boolean aguardandoNome = false;
    private static volatile float[] embeddingAguardando;
    private static volatile Mat framePendente;
    private static final Object frameLock = new Object();
    private static long ultimoLogRosto;
    private static int rostosNoUltimoLog = -1;

    private static boolean ultimoEstadoTemRosto = false;
    private static String ultimoRostoEnviado = "";

    private static volatile String usuarioAtivo = "";
    private static Properties perfilAtivo = new Properties();
    private static volatile ConsultaIA botAtual = null;

    private static volatile long limiteModoAtivo = 0;

    private static final String ARQUIVO_ROSTOS = "rostos_cadastrados.txt";
    private static final String ARQUIVO_CACHE = "cache_pesquisas.txt";
    private static Properties cacheProps = new Properties();

    public static void main(String[] args) throws Exception {
        silenciarLogsNativos(args);
        carregarCache();
        carregarRostosSalvos();

        Scanner scanner = new Scanner(System.in);

        // 1. Seleção do Modo de IA (Nuvem vs Local)
        escolherModoIA(scanner);

        // 2. Carrega as bibliotecas nativas do OpenCV e seleciona periféricos no console
        carregarOpenCV();
        int cameraIndex = escolherCamera(scanner);
        Mixer.Info microfoneEscolhido = MicrofoneInteligente.escolherMicrofone(scanner);

        // 3. Se o modo for Local, inicializa e carrega o Llama CLI na GPU
        if (!ConsultaIA.MODO_NUVEM) {
            LlamaCliManager.inicializarLlamaCli();
            LlamaCliManager.travarAteCarregar();
            System.out.println("[sistema] IA Local carregada!");
        } else {
            System.out.println("[sistema] Modo IA Nuvem ativo (Google Gemini).");
        }

        System.out.println("[sistema] Iniciando módulos de visão e audição...");

        // 4. Inicia o Microfone selecionado e a Câmera
        new Thread(() -> iniciarMicrofone(microfoneEscolhido)).start();

        try {
            iniciarCamera(cameraIndex);
        } catch (Exception e) {
            System.err.println("[ERRO] Falha fatal na câmera: " + e.getMessage());
        }
    }

    private static void escolherModoIA(Scanner scanner) {
        System.out.println("\n==================================================");
        System.out.println("        SELECAO DE ENGINE DE IA");
        System.out.println("==================================================");
        System.out.println(" 1 - IA Local (Llama CLI - Offline / NVIDIA 920M)");
        System.out.println(" 2 - IA Nuvem (Google Gemini - Online)");
        
        while (true) {
            System.out.print("Escolha o modo [1 ou 2] (Enter para Local [1]): ");
            System.out.flush();
            if (scanner == null) {
                scanner = new Scanner(System.in);
            }
            if (!scanner.hasNextLine()) {
                break;
            }
            String linha = scanner.nextLine().trim();
            if (linha.isEmpty() || linha.equals("1")) {
                ConsultaIA.MODO_NUVEM = false;
                System.out.println("[ia] Modo selecionado: IA Local (Llama CLI)");
                return;
            } else if (linha.equals("2")) {
                ConsultaIA.MODO_NUVEM = true;
                System.out.println("[ia] Modo selecionado: IA Nuvem (Google Gemini)");
                return;
            } else {
                System.out.println("[ia] Opcao invalida! Digite 1 para Local ou 2 para Nuvem.");
            }
        }
        ConsultaIA.MODO_NUVEM = false;
    }

    private static void carregarOpenCV() {
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t) {
            System.out.println("[opencv] Carregamento padrao OpenCV: " + t.getMessage());
        }
        String OS = System.getProperty("os.name").toLowerCase();
        if (OS.contains("win")) {
            try {
                URL res = ClassLoader.getSystemResource("lib/opencv_videoio_ffmpeg470_64.dll");
                if (res != null) {
                    System.load(new File(res.toURI()).getAbsolutePath());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static int escolherCamera(Scanner scanner) {
        System.out.println("\n==================================================");
        System.out.println("        SELECAO DE CAMERA");
        System.out.println("==================================================");
        System.out.println(" 0 - Camera Padrao (Index 0)");
        System.out.println(" 1 - Camera Secundaria (Index 1)");
        System.out.println(" 2 - Camera Terciaria (Index 2)");
        
        while (true) {
            System.out.print("Escolha o numero da camera [0, 1 ou 2] (Enter para 0): ");
            System.out.flush();
            if (scanner == null) {
                scanner = new Scanner(System.in);
            }
            if (!scanner.hasNextLine()) {
                break;
            }
            String linha = scanner.nextLine().trim();
            if (linha.isEmpty() || linha.equals("0")) {
                System.out.println("[cam] Selecionada camera index 0");
                return 0;
            }
            try {
                int escolha = Integer.parseInt(linha);
                if (escolha >= 0 && escolha <= 5) {
                    System.out.println("[cam] Selecionada camera index " + escolha);
                    return escolha;
                } else {
                    System.out.println("[cam] Opcao invalida! Escolha entre 0 e 5.");
                }
            } catch (NumberFormatException e) {
                System.out.println("[cam] Digite apenas um numero.");
            }
        }
        return 0;
    }

    private static void silenciarLogsNativos(String[] args) throws Exception {
        if ("1".equals(System.getenv("ASSISTENTE_SEM_LOG_NATIVO"))) {
            return;
        }
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> comando = new ArrayList<>();
        comando.add(javaBin);
        for (String argumento : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argumento.contains("jdwp")) {
                continue;
            }
            comando.add(argumento);
        }
        comando.add("-cp");
        comando.add(System.getProperty("java.class.path"));
        comando.add(AssistenteComVisao.class.getName());
        Collections.addAll(comando, args);

        ProcessBuilder processo = new ProcessBuilder(comando);
        processo.environment().put("OPENCV_LOG_LEVEL", "ERROR");
        processo.environment().put("OPENCV_OPENCL_RUNTIME", "disabled");
        processo.environment().put("ASSISTENTE_SEM_LOG_NATIVO", "1");
        processo.inheritIO();
        System.exit(processo.start().waitFor());
    }

    private static void iniciarCamera(int cameraIndex) throws OrtException {
        String cascadePath = arquivoExistente("src/main/resources/model/haarcascade_frontalface_default.xml");
        CascadeClassifier faceDetector = new CascadeClassifier(cascadePath);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        String modelo = arquivoExistente("src/main/resources/model/arcfaceresnet100-11-int8.onnx");
        OrtSession session = env.createSession(modelo, options);
        String inputName = session.getInputInfo().keySet().iterator().next();

        VideoCapture video = new VideoCapture();
        String OS = System.getProperty("os.name").toLowerCase();
        if (OS.contains("win")) {
            video.open(cameraIndex, Videoio.CAP_DSHOW);
        }
        if (!video.isOpened()) {
            video.open(cameraIndex);
        }
        if (!video.isOpened()) {
            System.err.println("[cam] não foi possível abrir a câmera " + cameraIndex);
            return;
        }

        Thread analise = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Mat frame;
                synchronized (frameLock) {
                    while (framePendente == null) {
                        try {
                            frameLock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    frame = framePendente;
                    framePendente = null;
                }
                try {
                    processAI(frame, faceDetector, env, session, inputName);
                } finally {
                    frame.release();
                }
            }
        });
        analise.setDaemon(true);
        analise.start();

        Mat img = new Mat();
        while (video.read(img)) {
            if (img.empty()) {
                continue;
            }
            synchronized (frameLock) {
                if (framePendente == null) {
                    framePendente = img.clone();
                    frameLock.notifyAll();
                }
            }

            for (DetectedFace face : currentFaces) {
                Point topLeft = new Point(face.rect.x, face.rect.y);
                Point bottomRight = new Point(face.rect.x + face.rect.width, face.rect.y + face.rect.height);
                Imgproc.rectangle(img, topLeft, bottomRight, face.color, 2);
            }

            HighGui.imshow("Olho da IA", img);
            if (HighGui.waitKey(30) != -1) {
                break;
            }
        }
        HighGui.destroyAllWindows();
        video.release();
        System.exit(0);
    }

    private static void processAI(Mat frame, CascadeClassifier faceDetector, OrtEnvironment env, OrtSession session, String inputName) {
        Mat grayImg = new Mat();
        if (frame.channels() == 4) {
            Imgproc.cvtColor(frame, grayImg, Imgproc.COLOR_BGRA2GRAY);
        } else if (frame.channels() == 3) {
            Imgproc.cvtColor(frame, grayImg, Imgproc.COLOR_BGR2GRAY);
        } else {
            frame.copyTo(grayImg);
        }

        double maxDim = 320.0;
        double scale = Math.min(1.0, maxDim / Math.max(frame.cols(), frame.rows()));
        Mat smallGray = new Mat();
        Imgproc.resize(grayImg, smallGray, new Size(Math.round(frame.cols() * scale), Math.round(frame.rows() * scale)));
        Imgproc.equalizeHist(smallGray, smallGray);

        MatOfRect faceDetections = new MatOfRect();
        faceDetector.detectMultiScale(smallGray, faceDetections, 1.2, 4, 0, new Size(30, 30), new Size());

        List<DetectedFace> newDetections = new ArrayList<>();
        String rostoMaisProximo = null;
        float[] embeddingPrincipal = null;
        double maiorArea = 0;

        for (Rect rect : faceDetections.toArray()) {
            rect.x = (int) (rect.x / scale);
            rect.y = (int) (rect.y / scale);
            rect.width = (int) (rect.width / scale);
            rect.height = (int) (rect.height / scale);
            if (rect.x < 0 || rect.y < 0 || rect.x + rect.width > frame.cols() || rect.y + rect.height > frame.rows()) {
                continue;
            }

            Mat croppedFace = new Mat(frame, rect);
            float[] liveEmbedding = extractFaceEmbedding(croppedFace, env, session, inputName);
            croppedFace.release();

            if (liveEmbedding == null) {
                newDetections.add(new DetectedFace(rect, "Desconhecido", 0, new Scalar(0, 0, 255)));
                continue;
            }

            String recognizedName = "Desconhecido";
            double maxSimilarity = -1;
            for (PersonRecord record : knownPersons) {
                double sim = cosineSimilarity(liveEmbedding, record.embedding);
                if (sim > maxSimilarity) {
                    maxSimilarity = sim;
                }
                if (sim > SIMILARITY_THRESHOLD && sim >= maxSimilarity) {
                    recognizedName = record.name;
                }
            }

            double area = rect.width * rect.height;
            if (area > maiorArea) {
                maiorArea = area;
                rostoMaisProximo = recognizedName;
                embeddingPrincipal = liveEmbedding;
            }
            Scalar colorBox = recognizedName.equals("Desconhecido") ? new Scalar(0, 0, 255) : new Scalar(0, 255, 0);
            newDetections.add(new DetectedFace(rect, recognizedName, maxSimilarity, colorBox));
        }

        if ("Desconhecido".equals(rostoMaisProximo) && embeddingPrincipal != null && !aguardandoNome && !estaFalando) {
            aguardandoNome = true;
            embeddingAguardando = embeddingPrincipal;
            new Thread(AssistenteComVisao::perguntarIdentidade).start();
        }

        if (!aguardandoNome && rostoMaisProximo != null && !rostoMaisProximo.equals("Desconhecido") && !rostoMaisProximo.equals(usuarioAtivo)) {
            trocarContextoUsuario(rostoMaisProximo, true);
        }

        currentFaces = newDetections;
        int quantidade = faceDetections.toArray().length;
        boolean temRosto = (quantidade > 0);

        if (temRosto != ultimoEstadoTemRosto || !usuarioAtivo.equals(ultimoRostoEnviado)) {
            ultimoEstadoTemRosto = temRosto;
            ultimoRostoEnviado = usuarioAtivo;
            JanelaFlutuante.definirPresenca(temRosto, usuarioAtivo);
        }

        grayImg.release();
        smallGray.release();
    }

    private static void perguntarIdentidade() {
        ConsultaIA bot = new ConsultaIA("Pergunte quem a pessoa desconhecida é, em português. Responda APENAS com TXT: [pergunta].");
        String pergunta = limparFala(bot.gerarResposta("Rosto novo detectado."));
        if (pergunta.isEmpty()) {
            pergunta = "Quem é você?";
        }

        JanelaFlutuante.mostrar("IA", pergunta);
        st_falar(pergunta);
    }

    private static void identificarPelaFala(String texto) {
        ConsultaIA bot = new ConsultaIA(
                "A pessoa disse quem é. Formato EXATO obrigatório:\nNOME: o nome\nTXT: cumprimento");
        String resposta = bot.gerarResposta(texto);
        if (resposta == null) {
            resposta = "";
        }

        String nome = extrairCampo(resposta, "NOME:").replaceAll("[.!?]+$", "");
        if (!nomeValido(nome)) {
            st_falar("Não entendi o nome. Pode repetir?");
            return;
        }

        float[] emb = embeddingAguardando;
        embeddingAguardando = null;
        aguardandoNome = false;

        if (emb != null) {
            knownPersons.add(new PersonRecord(nome, emb));
            salvarRostoNoHD(nome, emb);
        }

        trocarContextoUsuario(nome, false);
        String fala = extrairCampo(resposta, "TXT:");
        if (fala.isEmpty()) {
            fala = "Prazer, " + nome;
        }

        JanelaFlutuante.mostrar(nome, fala);
        st_falar(fala);
    }

    private static void trocarContextoUsuario(String novoUsuario, boolean saudar) {
        usuarioAtivo = novoUsuario;
        carregarPerfil(usuarioAtivo);

        String systemPrompt
                = "Você é o assistente virtual do usuário " + usuarioAtivo + ". É ESTRITAMENTE PROIBIDO responder com texto livre ou explicações.\n"
                + "VOCÊ DEVE OBRIGATORIAMENTE INICIAR SUA RESPOSTA COM UM DESTES 5 PREFIXOS:\n\n"
                + "1) PESQUISA: [termo] (Para buscar algo na internet e apenas ler)\n"
                + "2) PESQUISA_E_ARQUIVO: [termo] (Para pesquisar sobre um assunto e salvar o resultado num arquivo)\n"
                + "3) CMD: cmd /c start [url/comando] (Para abrir sites, youtube ou apps)\n"
                + "4) ARQUIVO: [nome.txt] | [conteúdo] (Para criar um documento com um texto que o usuário ditou)\n"
                + "5) TXT: [resposta curta] (Apenas para conversar, saudar ou responder perguntas)\n\n"
                + "=== EXEMPLOS DE COMO VOCÊ DEVE RESPONDER ===\n"
                + "Usuário: pesquise sobre o sol e salve num arquivo para mim\n" // Exemplo melhorado
                + "PESQUISA_E_ARQUIVO: Sol\n\n"
                + "Usuário: cria um arquivo chamado compras.txt escrito comprar maçã e pão\n"
                + "ARQUIVO: compras.txt | comprar maçã e pão\n\n"
                + "Usuário: abre o youtube\n"
                + "CMD: cmd /c start https://www.youtube.com\n\n"
                + "Usuário: quem foi Einstein?\n"
                + "PESQUISA: Albert Einstein\n\n"
                + "REGRA ABSOLUTA: RESPONDA APENAS COM O PREFIXO. NUNCA EXPLIQUE COMO FAZER. Não use emojis.";

        botAtual = new ConsultaIA(systemPrompt);
        if (!saudar) {
            return;
        }

        new Thread(() -> {
            MicrofoneInteligente.processandoComando = true;
            try {
                String promptSaudacao = "O usuário " + usuarioAtivo + " chegou. "
                        + "A última pesquisa foi '" + perfilAtivo.getProperty("ultima_pesquisa", "nada") + "'. "
                        + "Gere uma saudação natural curta em português. Não use prefixos. Não use emojis.";

                ConsultaIA botSaudacao = new ConsultaIA("Seja simpático. Sem emojis.");
                String saudacao = botSaudacao.gerarResposta(promptSaudacao).trim();
                saudacao = saudacao.replace("TXT:", "").replace("\"", "");

                JanelaFlutuante.mostrar("IA: Olá " + usuarioAtivo, saudacao);
                st_falar(saudacao);

                while (estaFalando || assistente.voz.PiperTTS.temTextoPendente()) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }
                }
            } finally {
                MicrofoneInteligente.processandoComando = false;
                JanelaFlutuante.fechar();
            }
        }).start();
    }

    private static void iniciarMicrofone(Mixer.Info microfoneInfo) {
        new MicrofoneInteligente(microfoneInfo) {
            @Override
            public void resultado(String texto) {
                if (aguardandoNome) {
                    new Thread(() -> {
                        MicrofoneInteligente.processandoComando = true;
                        try {
                            identificarPelaFala(texto);
                            while (estaFalando || assistente.voz.PiperTTS.temTextoPendente()) {
                                try {
                                    Thread.sleep(200);
                                } catch (InterruptedException e) {
                                }
                            }
                        } finally {
                            MicrofoneInteligente.processandoComando = false;
                            JanelaFlutuante.fechar();
                        }
                    }).start();
                    return;
                }

                if (botAtual == null || usuarioAtivo.isEmpty()) {
                    return;
                }

                String lower = texto.toLowerCase().trim();
                String semAcento = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");

                boolean contemAtivacao = semAcento.contains("assistente") || semAcento.contains("computador") || semAcento.contains("sistema");
                String textoFiltrado;

                if (contemAtivacao) {
                    textoFiltrado = texto.replaceAll("(?i)(assistente|computador|sistema)[.,]?", "").trim();
                    if (textoFiltrado.isEmpty()) {
                        limiteModoAtivo = System.currentTimeMillis() + 15000;
                        JanelaFlutuante.mostrar("Assistente", "Estou ouvindo...");
                        st_falar("Pode falar.");
                        return;
                    } else {
                        limiteModoAtivo = 0;
                    }
                } else {
                    if (System.currentTimeMillis() <= limiteModoAtivo) {
                        textoFiltrado = texto.trim();
                        limiteModoAtivo = 0;
                    } else {
                        return;
                    }
                }

                System.out.println("[" + usuarioAtivo + "] " + textoFiltrado);

                boolean pediuFechar = (lower.contains("fecha") || lower.contains("limpar"));
                if (pediuFechar && lower.contains("janela")) {
                    JanelaFlutuante.fechar();
                    st_falar("Fechado.");
                    return;
                }

                final String comandoParaIA = textoFiltrado;

                new Thread(() -> {
                    MicrofoneInteligente.processandoComando = true;
                    try {
                        String resposta =  botAtual.gerarResposta(comandoParaIA).toUpperCase();
                        if (resposta != null && !resposta.trim().isEmpty()) {
                            System.out.println("[ia] " + resposta.trim());
                            processarRespostaIA(resposta.trim());
                        }
                        while (estaFalando || assistente.voz.PiperTTS.temTextoPendente()) {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                            }
                        }
                    } finally {
                        MicrofoneInteligente.processandoComando = false;
                        JanelaFlutuante.fechar();
                    }
                }).start();
            }
        };
        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
        }
    }

    private static void processarRespostaIA(String resposta) {
        
        System.out.println("Resposta IA "+resposta);
        if (resposta == null || resposta.trim().isEmpty()) {
            st_falar("Não recebi uma resposta válida.");
            return;
        }

        String respUpper = resposta;

        boolean querPesquisa = contemAlgum(respUpper,
                "PESQUISA_E_ARQUIVO:", "PESQUISA:", "PESQUISA", "PESQUISAR",
                "BUSCAR", "BUSCA:", "SEARCH:", "SEARCH");
        boolean querArquivo = contemAlgum(respUpper,
                "PESQUISA_E_ARQUIVO:", "ARQUIVO:", "ARQUIVO", "ARQUIVAR",
                "SALVAR", "SALVAR_ARQUIVO", "CRIAR_ARQUIVO", "FILE:");

        // 1. PESQUISA E ARQUIVO (as duas intenções juntas, em qualquer ordem/variação)
        if (querPesquisa && querArquivo) {
            String query = extrairAposMarcador(resposta, respUpper,
                    "PESQUISA_E_ARQUIVO:", "PESQUISA:", "PESQUISAR:", "BUSCAR:", "SEARCH:");
            if (query.isEmpty()) {
                query = extrairLinhaValida(resposta);
            }
            query = limparMarcadores(query);

            st_falar("Buscando e salvando informações sobre " + query);

            String resultados = PesquisaWikipedia.pesquisar(query);
            System.out.println("Resultado query " + query + " ");
            System.out.println("Resultado pesquisa   " + resultados + " ");
            String resumo = botAtual.resumirPesquisa(query, resultados);
            System.out.println("Resumo da pesquisa " + resumo + " ");
            salvarNoPerfilBackground("ultima_pesquisa", query);

            String nomeArquivo = query.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (nomeArquivo.isEmpty()) {
                nomeArquivo = "pesquisa";
            }
            nomeArquivo = nomeArquivo + ".txt";

            String textoFinal = (resumo != null && !resumo.trim().isEmpty())
                    ? resumo
                    : "A IA não conseguiu resumir as informações de forma clara para: " + query;
            criarArquivoEExecutar(nomeArquivo, textoFinal);
        } // 2. SÓ ARQUIVO (ARQUIVO | ARQUIVAR | SALVAR | FILE, com ou sem pipe)
        else if (querArquivo) {
            String resto = extrairAposMarcador(resposta, respUpper,
                    "ARQUIVO:", "ARQUIVAR:", "SALVAR:", "FILE:", "CRIAR_ARQUIVO:");
            if (resto.isEmpty()) {
                resto = resposta.trim();
            }

            String[] partes = resto.split("\\|", 2);
            String nomeArquivo = limparMarcadores(partes[0]).replaceAll("[\"']", "");
            String conteudo = partes.length > 1 ? partes[1].trim() : "";

            if (nomeArquivo.isEmpty()) {
                nomeArquivo = "arquivo.txt";
            }
            if (!nomeArquivo.contains(".")) {
                nomeArquivo = nomeArquivo + ".txt";
            }
            if (conteudo.isEmpty()) {
                conteudo = "Arquivo criado pelo sistema. Você pediu a criação do arquivo, mas não ditou o texto ou a IA se confundiu.";
            }

            criarArquivoEExecutar(nomeArquivo, conteudo);
        } // 3. SÓ PESQUISA (PESQUISA | PESQUISAR | BUSCAR | SEARCH)
        else if (querPesquisa) {
            String query = extrairAposMarcador(resposta, respUpper,
                    "PESQUISA:", "PESQUISAR:", "BUSCAR:", "BUSCA:", "SEARCH:");
            if (query.isEmpty()) {
                query = extrairLinhaValida(resposta);
            }
            query = limparMarcadores(query).replaceAll("[\"']", "");

            String queryNormalizada = query.toLowerCase();
            String resumoEmCache = cacheProps.getProperty(queryNormalizada);
            if (resumoEmCache != null) {
                JanelaFlutuante.mostrar("Memória: " + query, resumoEmCache);
                st_falar("Eu já sei sobre isso. " + resumoEmCache);
            } else {
                st_falar("Buscando informações sobre " + query);
                String resultados = PesquisaWikipedia.pesquisar(query);
                String resumo = botAtual.resumirPesquisa(query, resultados);

                salvarNoCacheBackground(queryNormalizada, resumo);
                JanelaFlutuante.mostrar("Pesquisa: " + query, resumo);
                st_falar(resumo);
            }
            salvarNoPerfilBackground("ultima_pesquisa", query);
        } // 4. COMANDO (CMD | COMANDO | ABRIR | EXECUTAR)
        else {

            String respUpperLocal =resposta;

            boolean querCmd = contemAlgum(respUpperLocal,
                    "CMD:", "COMANDO:", "COMANDO", "ABRIR:", "EXECUTAR:", "EXEC:");

            if (querCmd) {
                String comando = extrairAposMarcador(resposta, respUpper,
                        "CMD:", "COMANDO:", "ABRIR:", "EXECUTAR:", "EXEC:");
                if (comando.isEmpty()) {
                    comando = extrairLinhaValida(resposta);
                }
                comando = limparMarcadores(comando);

                st_falar("Abrindo.");
                JanelaFlutuante.mostrar("Executando", comando);
                try {
                    Runtime.getRuntime().exec(comando);
                } catch (IOException e) {
                    st_falar("Falha ao executar a ação.");
                }
            } // 5. BATE-PAPO
            else {
                String fala = limparMarcadores(resposta.replace("TXT:", "")).trim();
                JanelaFlutuante.mostrar("Assistente", fala);
                st_falar(fala);
            }
        }
    }

    private static boolean contemAlgum(String textoUpper, String... termos) {
        for (String termo : termos) {
            if (textoUpper.contains(termo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pega o texto depois do primeiro marcador encontrado.
     */
    private static String extrairAposMarcador(String original, String upper, String... marcadores) {
        int melhor = -1;
        int tamanho = 0;
        for (String marcador : marcadores) {
            int idx = upper.indexOf(marcador);
            if (idx >= 0 && (melhor < 0 || idx < melhor)) {
                melhor = idx;
                tamanho = marcador.length();
            }
        }
        if (melhor < 0) {
            return "";
        }
        return extrairLinhaValida(original.substring(melhor + tamanho));
    }

    private static String limparMarcadores(String texto) {
        if (texto == null) {
            return "";
        }
        return texto
                .replaceAll("(?i)(PESQUISA_E_ARQUIVO:|PESQUISA:|PESQUISAR:|BUSCAR:|BUSCA:|SEARCH:|"
                        + "ARQUIVO:|ARQUIVAR:|SALVAR:|FILE:|CRIAR_ARQUIVO:|"
                        + "CMD:|COMANDO:|ABRIR:|EXECUTAR:|EXEC:|TXT:)", "")
                .trim();
    }

    // ARQUIVOS GERADOS E ABERTOS VIA NATIVO JAVA API
    private static void criarArquivoEExecutar(String nomeArquivo, String conteudo) {
        if (!nomeArquivo.contains(".")) {
            nomeArquivo += ".txt";
        }

        // Bloqueio extra: garantir que não grave um arquivo literal com string nula/vazia
        if (conteudo == null || conteudo.trim().isEmpty()) {
            conteudo = "Conteúdo não especificado.";
        }

        File arquivo = new File(System.getProperty("user.home") + File.separator + "Desktop", nomeArquivo);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(arquivo), StandardCharsets.UTF_8)) {
            writer.write(conteudo);
            writer.flush(); // Garante que tudo foi gravado no disco
            st_falar("Arquivo criado na sua área de trabalho.");
            JanelaFlutuante.mostrar("Arquivo Criado", "Área de Trabalho\\" + arquivo.getName());

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(arquivo);
            }
        } catch (IOException e) {
            System.err.println("[erro] Falha ao manipular o arquivo: " + e.getMessage());
            st_falar("Tive um problema ao salvar o arquivo.");
        }
    }

    private static String extrairCampo(String resposta, String prefixo) {
        if (resposta == null) {
            return "";
        }
        String alvo = prefixo.toUpperCase();
        for (String linha : resposta.split("\\r?\\n")) {
            String texto = linha.trim();
            int inicio = texto.toUpperCase().indexOf(alvo);
            if (inicio < 0) {
                continue;
            }
            String valor = texto.substring(inicio + prefixo.length()).trim();
            int proximo = valor.toUpperCase().indexOf("TXT:");
            if (prefixo.equalsIgnoreCase("TXT:")) {
                proximo = valor.toUpperCase().indexOf("NOME:");
            }
            if (proximo >= 0) {
                valor = valor.substring(0, proximo).trim();
            }
            return valor.replace("\"", "").trim();
        }
        return "";
    }

    private static boolean nomeValido(String nome) {
        if (nome == null) {
            return false;
        }
        String limpo = nome.trim().replaceAll("[.!?]+$", "");
        return !limpo.isEmpty() && !limpo.equals("?") && limpo.length() <= 40 && limpo.matches("[\\p{L}][\\p{L} .'-]*");
    }

    private static String limparFala(String texto) {
        if (texto == null) {
            return "";
        }
        String fala = extrairCampo(texto, "TXT:");
        if (fala.isEmpty()) {
            fala = texto;
        }
        return fala.replace("TXT:", "").replace("NOME:", "").replace("\"", "").trim();
    }

    private static String extrairLinhaValida(String texto) {
        return texto == null ? "" : texto.split("\\r?\\n")[0].trim();
    }

    // Persistência
    private static void carregarPerfil(String nome) {
        perfilAtivo.clear();
        try {
            File arquivo = new File("perfil_" + nome.toLowerCase() + ".txt");
            if (arquivo.exists()) {
                try (FileInputStream in = new FileInputStream(arquivo)) {
                    perfilAtivo.load(in);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void salvarNoPerfilBackground(String k, String v) {
        String n = usuarioAtivo;
        new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream("perfil_" + n.toLowerCase() + ".txt")) {
                perfilAtivo.setProperty(k, v);
                perfilAtivo.store(out, "Memória do usuário: " + n);
            } catch (IOException ignored) {
            }
        }).start();
    }

    private static void carregarCache() {
        try {
            File arq = new File(ARQUIVO_CACHE);
            if (arq.exists()) {
                try (FileInputStream in = new FileInputStream(arq)) {
                    cacheProps.load(in);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void salvarNoCacheBackground(String k, String v) {
        new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream(ARQUIVO_CACHE)) {
                cacheProps.setProperty(k, v);
                cacheProps.store(out, "Cache Global de Pesquisas");
            } catch (IOException ignored) {
            }
        }).start();
    }

    private static void carregarRostosSalvos() {
        try {
            File arq = new File(ARQUIVO_ROSTOS);
            if (!arq.exists()) {
                return;
            }
            BufferedReader br = new BufferedReader(new FileReader(arq));
            String linha;
            while ((linha = br.readLine()) != null) {
                String[] partes = linha.split("=");
                if (partes.length == 2) {
                    String[] floatsStr = partes[1].split(",");
                    float[] embedding = new float[floatsStr.length];
                    for (int i = 0; i < floatsStr.length; i++) {
                        embedding[i] = Float.parseFloat(floatsStr[i]);
                    }
                    knownPersons.add(new PersonRecord(partes[0], embedding));
                }
            }
            br.close();
        } catch (Exception ignored) {
        }
    }

    private static void salvarRostoNoHD(String nome, float[] embedding) {
        new Thread(() -> {
            try (FileWriter fw = new FileWriter(ARQUIVO_ROSTOS, true)) {
                StringBuilder sb = new StringBuilder(nome).append("=");
                for (int i = 0; i < embedding.length; i++) {
                    sb.append(embedding[i]);
                    if (i < embedding.length - 1) {
                        sb.append(",");
                    }
                }
                fw.write(sb.append("\n").toString());
            } catch (IOException ignored) {
            }
        }).start();
    }

    private static String arquivoExistente(String relativo) {
        File arquivo = new File(relativo);
        if (!arquivo.isFile()) {
            arquivo = new File(System.getProperty("user.dir"), relativo);
        }
        if (!arquivo.isFile()) {
            throw new IllegalStateException("Arquivo ausente: " + arquivo.getAbsolutePath());
        }
        return arquivo.getAbsolutePath();
    }

    private static float[] extractFaceEmbedding(Mat croppedFace, OrtEnvironment env, OrtSession session, String inputName) {
        OnnxTensor tensor = null;
        OrtSession.Result output = null;
        Mat resized = new Mat();
        try {
            Imgproc.resize(croppedFace, resized, new Size(112, 112));
            Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2RGB);

            byte[] data = new byte[112 * 112 * 3];
            resized.get(0, 0, data);
            float[] chw = new float[3 * 112 * 112];
            int planeSize = 112 * 112;

            for (int h = 0; h < 112; h++) {
                int rowOffset = h * 112;
                for (int w = 0; w < 112; w++) {
                    int spatialIndex = rowOffset + w;
                    int pixelOffset = spatialIndex * 3;
                    chw[spatialIndex] = ((data[pixelOffset] & 0xFF) - 127.5f) / 127.5f;
                    chw[planeSize + spatialIndex] = ((data[pixelOffset + 1] & 0xFF) - 127.5f) / 127.5f;
                    chw[planeSize * 2 + spatialIndex] = ((data[pixelOffset + 2] & 0xFF) - 127.5f) / 127.5f;
                }
            }
            tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, 112, 112});
            output = session.run(Collections.singletonMap(inputName, tensor));
            return ((float[][]) output.get(0).getValue())[0];
        } catch (Exception e) {
            return null;
        } finally {
            resized.release();
            try {
                if (output != null) {
                    output.close();
                }
                if (tensor != null) {
                    tensor.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}