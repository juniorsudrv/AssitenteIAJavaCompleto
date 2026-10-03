package assistente.ia;

import java.io.*;
import java.util.*;

public class LlamaCliManager {

    private static Process process = null;
    private static BufferedWriter writer;
    private static BufferedReader reader;

    private static boolean isReady = false;
    private static boolean isGerando = false;
    private static final Object lock = new Object();
    private static final StringBuilder outputBuffer = new StringBuilder();

    public static void inicializarLlamaCli() {
        System.out.println("\n==================================================");
        System.out.println("       INICIANDO LLAMA (CLI ITERATIVO - NVIDIA 920M)");
        System.out.println("==================================================");

        if (process != null && process.isAlive()) {
            System.out.println("[llama-cli] O processo ja esta ativo.");
            return;
        }

        File modeloEscolhido = buscarModelo();
        if (modeloEscolhido == null) {
            System.err.println("[llama-cli] Nenhum modelo GGUF foi selecionado. Abortando sistema.");
            System.exit(1);
        }

        File executavelCli = buscarExecutavelLlamaCli();
        if (executavelCli == null) {
            System.err.println("[llama-cli] Executavel llama-cli.exe nao encontrado. Abortando sistema.");
            System.exit(1);
        }

        iniciarProcesso(executavelCli, modeloEscolhido);
    }

  public static void travarAteCarregar() {
        if (process == null || !process.isAlive()) {
            System.err.println("[llama-cli] ERRO CRÍTICO: O processo do LLM não está rodando. Encerrando.");
            System.exit(1);
        }

        System.out.println("[llama-cli] Carregando modelo... Aguarde (pode levar alguns segundos).");
        synchronized (lock) {
            while (!isReady && process != null && process.isAlive()) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (isReady) {
            System.out.println("[llama-cli] Modelo carregado na memoria e PRONTO para uso!");
        } else {
            System.err.println("[llama-cli] Falha no carregamento. O processo morreu inesperadamente.");
            System.err.println("==================================================");
            System.err.println("LOG DE ERRO DO LLAMA ANTES DE MORRER:");
            System.err.println(outputBuffer.toString());
            System.err.println("==================================================");
            System.exit(1);
        }
    }
  
  
private static void iniciarProcesso(File exe, File modelo) {
        try {
            int nucleos = Runtime.getRuntime().availableProcessors();
            int threads = Math.max(2, Math.min(4, nucleos / 2));

            ProcessBuilder pb = new ProcessBuilder(
                    exe.getAbsolutePath(),
                    "-m", modelo.getAbsolutePath(),
                    "-c", "1024",              
                    "-ngl", "80",              // Reduzido para evitar estourar a VRAM da NVIDIA 920M (1GB/2GB)
                    "-b", "512",               
                    "-t", String.valueOf(threads),
                    "--simple-io",              // Essencial para comunicação desinterrompida via subprocesso Java
                    "-co", "off",               // Desativa cores ANSI que quebram o parsing do terminal
                    "--reverse-prompt", "USER_PROMPT>",      
                    "--prompt", "SISTEMA INICIADO\nUSER_PROMPT>" 
            );

            // Garante que o processo enxergue as DLLs
            pb.directory(exe.getParentFile()); 

            pb.redirectErrorStream(true); 
            process = pb.start();

            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (process != null && process.isAlive()) {
                    System.out.println("[llama-cli] Encerrando forcado o llama-cli...");
                    process.destroyForcibly();
                }
            }));

            iniciarLeitorDeTerminal();

        } catch (Exception e) {
            System.err.println("[llama-cli] Erro ao iniciar processo: " + e.getMessage());
        }
    }

    private static void iniciarLeitorDeTerminal() {
        new Thread(() -> {
            try {
                int c;
                while ((c = reader.read()) != -1) {
                    synchronized (lock) {
                        outputBuffer.append((char) c);
                        String buf = outputBuffer.toString();

                        if (buf.endsWith("USER_PROMPT>") || buf.endsWith("> ") || buf.endsWith(">\n") || buf.endsWith(">")) {
                            if (!isReady) {
                                isReady = true; 
                            }
                            if (isGerando) {
                                isGerando = false; 
                            }
                            lock.notifyAll(); 
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[llama-cli] Leitor de terminal desconectado.");
            } finally {
                synchronized (lock) {
                    isReady = false;
                    isGerando = false;
                    lock.notifyAll();
                }
            }
        }, "LlamaCli-Reader").start();
    }

    public static String perguntar(String systemPrompt, String pergunta) {
        if (process == null || !process.isAlive() || !isReady) {
            return null;
        }

        synchronized (lock) {
            outputBuffer.setLength(0); 
            isGerando = true;

            String textoSeguro = pergunta.replace("\n", " ").trim();
            String sysSeguro = (systemPrompt == null ? "" : systemPrompt.replace("\n", " ").trim());
            String mensagemEnvio = "[Sistema: " + sysSeguro + "] Usuário: " + textoSeguro + "\n";

            try {
                writer.write(mensagemEnvio);
                writer.flush();

                long inicio = System.currentTimeMillis();
                long timeoutMs = 30000; // Safety timeout: 30 segundos max

                while (isGerando && process.isAlive()) {
                    lock.wait(500);
                    if (System.currentTimeMillis() - inicio > timeoutMs) {
                        System.err.println("[llama-cli] Timeout na resposta da IA. Continuando...");
                        isGerando = false;
                        break;
                    }
                }

                String respostaBruta = outputBuffer.toString();

                respostaBruta = respostaBruta.replaceAll("(?s)\\[ Prompt:.*?\\]", "");
                if (respostaBruta.endsWith("USER_PROMPT>")) {
                    respostaBruta = respostaBruta.substring(0, respostaBruta.length() - "USER_PROMPT>".length());
                }
                if (respostaBruta.endsWith(">")) {
                    respostaBruta = respostaBruta.substring(0, respostaBruta.length() - 1);
                }

                if (respostaBruta.contains(mensagemEnvio)) {
                    respostaBruta = respostaBruta.replace(mensagemEnvio, "");
                }

                return respostaBruta.trim();

            } catch (Exception e) {
                System.err.println("[llama-cli] Erro ao comunicar com IA: " + e.getMessage());
                isGerando = false;
                return null;
            }
        }
    }

    public static boolean isPronto() {
        return isReady && process != null && process.isAlive();
    }

   private static File buscarModelo() {
        List<File> modelosEncontrados = new ArrayList<>();
        List<String> diretorios = Arrays.asList("models", "src/main/resources/model", "src/main/resources/models", ".");

        for (String dirPath : diretorios) {
            File dir = new File(dirPath);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".gguf"));
                if (files != null) {
                    for (File f : files) {
                        if (!modelosEncontrados.contains(f)) {
                            modelosEncontrados.add(f);
                        }
                    }
                }
            }
        }

        if (modelosEncontrados.isEmpty()) {
            Scanner scanner = new Scanner(System.in);
            System.out.print("[llama-cli] Caminho do modelo .gguf nao encontrado. Digite o caminho manualmente: ");
            String caminho = scanner.nextLine().trim();
            return caminho.isEmpty() ? null : new File(caminho);
            
        } else if (modelosEncontrados.size() == 1) {
            System.out.println("[llama-cli] Modelo carregado automaticamente: " + modelosEncontrados.get(0).getName());
            return modelosEncontrados.get(0);
            
        } else {
            System.out.println("\nModelos .gguf disponiveis:");
            for (int i = 0; i < modelosEncontrados.size(); i++) {
                File f = modelosEncontrados.get(i);
                double tamanhoGB = f.length() / (1024.0 * 1024.0 * 1024.0);
                System.out.printf(" [%d] %s (%.2f GB)\n", (i + 1), f.getName(), tamanhoGB);
            }
            
            System.out.print("\nEscolha o numero do modelo (pressione ENTER para a opcao [1]): ");
            Scanner scanner = new Scanner(System.in);
            String entrada = scanner.nextLine().trim();

            int escolha = 1;
            if (!entrada.isEmpty()) {
                try {
                    escolha = Integer.parseInt(entrada);
                } catch (NumberFormatException ignored) {}
            }

            if (escolha >= 1 && escolha <= modelosEncontrados.size()) {
                return modelosEncontrados.get(escolha - 1);
            } else {
                return modelosEncontrados.get(0);
            }
        }
    }

    private static File buscarExecutavelLlamaCli() {
        List<String> possiveis = Arrays.asList(
                "src/main/resources/llama/llama-cli.exe",
                "src/main/resources/llama/llama-cli",
                "llama/llama-cli.exe",
                "llama-cli.exe",
                "src/main/resources/llama/main.exe", 
                "llama/main.exe",
                "main.exe"
        );
        for (String c : possiveis) {
            File f = new File(c);
            if (f.exists() && f.isFile()) return f;
        }

        // SE NÃO ACHAR, PEDE PARA O USUÁRIO DIGITAR
        Scanner scanner = new Scanner(System.in);
        System.out.print("[llama-cli] Executavel llama-cli.exe nao encontrado nos arquivos do projeto.\nDigite o caminho completo (ex: C:\\llama\\llama-cli.exe): ");
        String caminho = scanner.nextLine().trim();
        
        if (!caminho.isEmpty()) {
            File f = new File(caminho);
            if (f.exists() && f.isFile()) return f;
        }

        return null;
    }
    
 

 

    public static void main(String[] args) {

        try {
            // Inicializa o modelo
            LlamaCliManager.inicializarLlamaCli();

            // Aguarda carregamento completo
            LlamaCliManager.travarAteCarregar();

            if (!LlamaCliManager.isPronto()) {
                System.err.println("Llama não ficou pronto.");
                return;
            }

            Scanner scanner = new Scanner(System.in);

            System.out.println("\n====================================");
            System.out.println("      TESTE INTERATIVO LLAMA");
            System.out.println("Digite 'sair' para encerrar.");
            System.out.println("====================================\n");

            while (true) {

                System.out.print("Você: ");
                String pergunta = scanner.nextLine();

                if (pergunta == null || pergunta.trim().isEmpty()) {
                    continue;
                }

                if ("sair".equalsIgnoreCase(pergunta.trim())) {
                    System.out.println("Encerrando...");
                    break;
                }

                String resposta = LlamaCliManager.perguntar(
                        "Você é um assistente útil e objetivo.",
                        pergunta
                );

                System.out.println("\nIA:");
                System.out.println(resposta);
                System.out.println();
            }

            scanner.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
 
}