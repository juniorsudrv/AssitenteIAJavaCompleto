package assistente.voz;

import javax.sound.sampled.*;
import java.io.*;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MicrofoneInteligente {

    private static final int SAMPLE_RATE = 16000;
    private static final int PRE_ROLL_MS = 400;
    private static final int SILENCIO_MS = 1000;
    private static final int INICIO_MS = 80;
    private static final int FALA_MINIMA_MS = 300;

    // NOVO: limite máximo de gravação contínua (evita travamento em "gravando")
    private static final int GRAVACAO_MAXIMA_MS = 15000;
    // NOVO: watchdog absoluto para destravar o estado caso algo dê errado
    private static final int WATCHDOG_RESET_MS = 20000;

    private final String whisperExecutablePath;
    private final String whisperModelPath;

    public static volatile boolean transcrevendo = false;
    public static volatile boolean processandoComando = false;

    private final BlockingQueue<byte[]> filaProcessamento = new LinkedBlockingQueue<>();
    private final File entradaTemporaria = new File(System.getProperty("java.io.tmpdir"), "assistente-entrada.wav");
    private volatile boolean rodando = true;

    static boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    static String executavelWhisper = isWindows ? "src/main/resources/whisper/whisper-cli.exe" : "src/main/resources/whisper-bin-ubuntu-x64/whisper-cli";
    static String modeloWhisper = "src/main/resources/model/ggml-base.bin";

    public MicrofoneInteligente() {
        this(escolherMicrofone(new Scanner(System.in)));
    }

    public MicrofoneInteligente(Mixer.Info escolhido) {
        this.whisperExecutablePath = executavelWhisper;
        this.whisperModelPath = modeloWhisper;
        iniciarOuvinte(escolhido);
        iniciarTranscritor();
    }

    public static Mixer.Info escolherMicrofone() {
        return escolherMicrofone(new Scanner(System.in));
    }

    public static Mixer.Info escolherMicrofone(Scanner scanner) {
        List<Mixer.Info> microfones = obterMicrofonesDisponiveis();
        if (microfones.isEmpty()) {
            System.out.println("[voz] nenhum microfone compativel, usando o padrao");
            return null;
        }
        System.out.println("\n==================================================");
        System.out.println("        SELECAO DE MICROFONE");
        System.out.println("==================================================");
        System.out.println("0 - Padrao do Sistema");
        for (int i = 0; i < microfones.size(); i++) {
            System.out.println((i + 1) + " - " + microfones.get(i).getName());
        }

        while (true) {
            System.out.print("Numero do microfone: ");
            System.out.flush();
            if (scanner == null) {
                scanner = new Scanner(System.in);
            }
            if (!scanner.hasNextLine()) {
                break;
            }
            String linha = scanner.nextLine().trim();
            if (linha.isEmpty() || linha.equals("0")) {
                System.out.println("[voz] Selecionado: Microfone Padrao (0)");
                return null;
            }
            try {
                int escolha = Integer.parseInt(linha);
                if (escolha >= 1 && escolha <= microfones.size()) {
                    Mixer.Info escolhido = microfones.get(escolha - 1);
                    System.out.println("[voz] Selecionado: " + escolhido.getName());
                    return escolhido;
                } else {
                    System.out.println("[voz] Opcao invalida. Escolha entre 0 e " + microfones.size());
                }
            } catch (NumberFormatException e) {
                System.out.println("[voz] Digite apenas um numero.");
            }
        }
        return null;
    }

    private void iniciarOuvinte(Mixer.Info mixerInfo) {
        Thread threadOuvinte = new Thread(() -> {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            try {
                TargetDataLine microfone = abrirMicrofone(mixerInfo, info, format);
                microfone.start();

                byte[] buffer = new byte[2048];
                boolean estaGravando = false;
                long tempoUltimaFala = 0;
                long tempoInicioGravacao = 0;          // NOVO: timestamp de início de gravação
                long tempoProcessamentoInicio = 0;
                int holdMs = 0;
                int falaMs = 0;
                double limiar = calibrarLimiar(microfone, buffer);
                System.out.println("[voz] limiar ajustado: " + Math.round(limiar));
                PreRoll preRoll = new PreRoll(msParaBytes(PRE_ROLL_MS));
                ByteArrayOutputStream audioAtual = new ByteArrayOutputStream();

                while (rodando) {
                    int bytesLidos = microfone.read(buffer, 0, buffer.length);
                    if (bytesLidos <= 0) continue;

                    int duracaoMs = bytesLidos * 1000 / (SAMPLE_RATE * 2);

                    // Trava de segurança: reseta processandoComando se ficar travado por mais de 25s
                    if (processandoComando) {
                        if (tempoProcessamentoInicio == 0) {
                            tempoProcessamentoInicio = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - tempoProcessamentoInicio > 25000) {
                            System.err.println("[voz] AVISO: Trava de processandoComando excedeu 25s. Liberação forçada.");
                            processandoComando = false;
                            tempoProcessamentoInicio = 0;
                        }
                    } else {
                        tempoProcessamentoInicio = 0;
                    }

                    if (PiperTTS.temTextoPendente() || processandoComando) {
                        estaGravando = false;
                        holdMs = 0;
                        falaMs = 0;
                        audioAtual.reset();
                        preRoll.clear();
                        continue;
                    }

                    // NOVO: Watchdog absoluto — se ficou gravando por muito tempo, reseta tudo.
                    if (estaGravando && (System.currentTimeMillis() - tempoInicioGravacao) > WATCHDOG_RESET_MS) {
                        System.err.println("[voz] WATCHDOG: gravação travada por " + WATCHDOG_RESET_MS + "ms. Resetando estado.");
                        estaGravando = false;
                        holdMs = 0;
                        falaMs = 0;
                        audioAtual.reset();
                        preRoll.clear();
                        continue;
                    }

                    double volumeAtual = calcularVolume(buffer, bytesLidos);
                    boolean falando = volumeAtual > limiar;

                    if (!estaGravando) {
                        preRoll.add(buffer, bytesLidos);
                        if (falando) {
                            holdMs += duracaoMs;
                            if (holdMs >= INICIO_MS) {
                                estaGravando = true;
                                falaMs = holdMs;
                                tempoUltimaFala = System.currentTimeMillis();
                                tempoInicioGravacao = System.currentTimeMillis(); // NOVO
                                preRoll.writeTo(audioAtual);
                                preRoll.clear();
                                System.out.println("[voz] gravando");
                            }
                        } else {
                            holdMs = 0;
                        }
                    } else {
                        audioAtual.write(buffer, 0, bytesLidos);

                        // NOVO: considera também o timeout máximo de gravação
                        boolean timeoutGravacao =
                                (System.currentTimeMillis() - tempoInicioGravacao) > GRAVACAO_MAXIMA_MS;

                        if (falando && !timeoutGravacao) {
                            falaMs += duracaoMs;
                            tempoUltimaFala = System.currentTimeMillis();
                        } else if (timeoutGravacao
                                || (System.currentTimeMillis() - tempoUltimaFala > SILENCIO_MS)) {

                            if (timeoutGravacao) {
                                System.err.println("[voz] Timeout de gravação (" + GRAVACAO_MAXIMA_MS
                                        + "ms). Encerrando e enviando o que capturou.");
                            }

                            estaGravando = false;
                            holdMs = 0;
                            if (falaMs >= FALA_MINIMA_MS) {
                                filaProcessamento.offer(audioAtual.toByteArray());
                            }
                            falaMs = 0;
                            audioAtual.reset();
                        }
                    }
                }
                microfone.close();
            } catch (Exception e) {
                System.err.println("Erro ao abrir o microfone: " + e.getMessage());
            }
        });
        threadOuvinte.setDaemon(true);
        threadOuvinte.start();
    }

    private void iniciarTranscritor() {
        Thread threadTranscritor = new Thread(() -> {
            while (rodando) {
                try {
                    byte[] dadosAudio = filaProcessamento.take();
                    transcrevendo = true;
                    String textoTranscrito = "";
                    try {
                        escreverWav(entradaTemporaria, dadosAudio);
                        textoTranscrito = chamarWhisper(entradaTemporaria);
                    } finally {
                        transcrevendo = false;
                        entradaTemporaria.delete();
                    }

                    if (textoUtil(textoTranscrito)) {
                        System.out.println("[voz] ouviu: " + textoTranscrito);
                        resultado(textoTranscrito);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[voz] erro no transcritor: " + e.getMessage());
                }
            }
        });
        threadTranscritor.setDaemon(true);
        threadTranscritor.start();
    }

    public void resultado(String texto) {
        // Metodo sobrescrito no AssistenteComVisao
    }

    private static double calcularVolume(byte[] buffer, int bytesLidos) {
        long soma = 0;
        for (int i = 0; i < bytesLidos; i += 2) {
            int amostra = (buffer[i + 1] << 8) | (buffer[i] & 0xFF);
            soma += amostra * amostra;
        }
        return Math.sqrt(soma / (bytesLidos / 2.0));
    }

    private static void escreverWav(File arquivo, byte[] dadosAudio) throws IOException {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        if (dadosAudio.length < format.getFrameSize()) {
            throw new IOException("audio vazio");
        }
        int quadros = dadosAudio.length / format.getFrameSize();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(dadosAudio);
             AudioInputStream ais = new AudioInputStream(bais, format, quadros)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, arquivo);
        }
    }

    private String chamarWhisper(File arquivoAudio) {
        StringBuilder textoFinal = new StringBuilder();
        try {
            int threads = 2;
            ProcessBuilder pb = new ProcessBuilder(
                    whisperExecutablePath,
                    "-m", whisperModelPath,
                    "-l", "pt",
                    "-nt",
                    "-np",
                    "-sns",
                    "-t", String.valueOf(threads),
                    "--prompt", "Português do Brasil.",
                    "-f", arquivoAudio.getAbsolutePath()
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process processo = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(processo.getInputStream(), "UTF-8"))) {
                String linha;
                while ((linha = reader.readLine()) != null) {
                    linha = linha.trim();
                    if (linha.isEmpty() || linha.startsWith("[")) continue;
                    textoFinal.append(linha).append(' ');
                }
            }
            processo.waitFor();
        } catch (Exception e) {
            System.err.println("Falha ao transcrever: " + e.getMessage());
        }
        return textoFinal.toString().replaceAll("\\[.*?\\]", "").trim();
    }

    private static boolean textoUtil(String texto) {
        if (texto == null) return false;
        String limpo = texto.trim();
        if (limpo.length() < 2 || limpo.matches("[\\p{Punct}\\s]+")) return false;

        String normalizado = limpo.toLowerCase();
        String semAcento = Normalizer.normalize(normalizado, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");

        return !normalizado.contains("amara.org")
                && !normalizado.contains("inscreva-se")
                && !semAcento.contains("obrigado")
                && !semAcento.contains("tchau")
                && !semAcento.startsWith("legendas")
                && !semAcento.startsWith("transcricao")
                && !semAcento.startsWith("portugues do brasil");
    }

    // ---------------------------------------------------------------
    // CALIBRAÇÃO MELHORADA: mais amostras + percentil + fator adaptativo
    // ---------------------------------------------------------------
    private static double calibrarLimiar(TargetDataLine microfone, byte[] buffer) {
        double[] volumes = new double[30]; // era 8 — amostragem mais robusta
        for (int i = 0; i < volumes.length; i++) {
            int lidos = microfone.read(buffer, 0, buffer.length);
            volumes[i] = lidos > 0 ? calcularVolume(buffer, lidos) : 0;
        }
        Arrays.sort(volumes);

        double mediana = volumes[volumes.length / 2];
        double p90 = volumes[(int) (volumes.length * 0.9)];

        // Limiar com folga sobre o ruído de fundo, mas nunca menor que 250
        // nem maior que 1500 (evita limiar absurdo em ambientes barulhentos).
        double limiar = Math.max(mediana * 3.0, p90 * 1.5);
        return Math.min(1500, Math.max(250, limiar));
    }

    private static TargetDataLine abrirMicrofone(Mixer.Info escolhido, DataLine.Info info, AudioFormat format) throws LineUnavailableException {
        if (escolhido != null) {
            Mixer mixer = AudioSystem.getMixer(escolhido);
            TargetDataLine linha = (TargetDataLine) mixer.getLine(info);
            linha.open(format);
            return linha;
        }
        try {
            TargetDataLine padrao = (TargetDataLine) AudioSystem.getLine(info);
            padrao.open(format);
            return padrao;
        } catch (Exception primeiro) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (!mixer.isLineSupported(info)) continue;
                try {
                    TargetDataLine linha = (TargetDataLine) mixer.getLine(info);
                    linha.open(format);
                    return linha;
                } catch (Exception ignored) {}
            }
            throw new LineUnavailableException("Nenhum microfone aceita 16 kHz mono.");
        }
    }

    private static int msParaBytes(int ms) {
        return SAMPLE_RATE * 2 * ms / 1000;
    }

    private static final class PreRoll {
        private final int maxBytes;
        private final ArrayDeque<byte[]> partes = new ArrayDeque<>();
        private int tamanho;

        private PreRoll(int maxBytes) { this.maxBytes = maxBytes; }

        private void add(byte[] dados, int tamanhoLido) {
            partes.addLast(Arrays.copyOf(dados, tamanhoLido));
            tamanho += tamanhoLido;
            while (tamanho > maxBytes && !partes.isEmpty()) {
                tamanho -= partes.removeFirst().length;
            }
        }

        private void writeTo(ByteArrayOutputStream destino) {
            for (byte[] parte : partes) destino.write(parte, 0, parte.length);
        }

        private void clear() {
            partes.clear();
            tamanho = 0;
        }
    }

    public static List<Mixer.Info> obterMicrofonesDisponiveis() {
        List<Mixer.Info> microfonesCompativeis = new ArrayList<>();
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(info)) {
                microfonesCompativeis.add(mixerInfo);
            }
        }
        return microfonesCompativeis;
    }
}