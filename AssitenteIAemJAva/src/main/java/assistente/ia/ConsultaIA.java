package assistente.ia;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cliente adaptado para alternar dinamicamente entre LlamaCliManager (IA Local)
 * e PromptIARequestGoogle (IA Nuvem - Google Gemini).
 */
public class ConsultaIA {

    public static volatile boolean MODO_NUVEM = false;

    private static final int HISTORICO_GUARDADO = 16;
    private String promptSistema;
    private final List<String> historicoConversa = new ArrayList<>();
    private PromptIARequestGoogle googleBot;

    public boolean nomeExiste = false;

    public ConsultaIA(String promptSistema) {
        this.promptSistema = promptSistema;
        if (MODO_NUVEM) {
            this.googleBot = new PromptIARequestGoogle(promptSistema);
        }
    }

    public String getPROMPT_SISTEMA() {
        return promptSistema;
    }

    public void setPROMPT_SISTEMA(String prompt) {
        this.promptSistema = prompt;
        if (googleBot != null) {
            googleBot.setPROMPT_SISTEMA(prompt);
        }
    }

    public void limparHistorico() {
        historicoConversa.clear();
        if (googleBot != null) {
            googleBot.limparHistorico();
        }
    }

    public synchronized void adicionarAoHistorico(String papel, String texto) {
        if (texto == null || texto.isBlank()) {
            return;
        }
        historicoConversa.add(papel + ": " + texto.trim());

        while (historicoConversa.size() > HISTORICO_GUARDADO) {
            historicoConversa.remove(0);
        }
        if (googleBot != null) {
            googleBot.adicionarAoHistorico(papel, texto);
        }
    }

    public boolean llmNaoInicializado() {
        if (MODO_NUVEM) {
            return false;
        }
        return !LlamaCliManager.isPronto();
    }

    public synchronized String gerarResposta(String pergunta) {
        return gerarResposta(pergunta, 48);
    }

    public synchronized String gerarResposta(String pergunta, int maxTokens) {
        if (pergunta == null || pergunta.isBlank()) {
            return null;
        }

        if (MODO_NUVEM) {
            if (googleBot == null) {
                googleBot = new PromptIARequestGoogle(promptSistema);
            }
            return googleBot.gerarResposta(pergunta);
        }

        adicionarAoHistorico("user", pergunta);

        try {
            String resp = LlamaCliManager.perguntar(promptSistema, pergunta);
            
            if (resp == null || resp.isBlank()) {
                removerUltimoUsuario();
                return null;
            }
            
            adicionarAoHistorico("assistant", resp);
            return resp;
        } catch (Exception e) {
            removerUltimoUsuario();
            System.out.println("[ia] Erro ao comunicar com CLI: " + e.getMessage());
            return null;
        }
    }

    public static String completar(String sistema, String usuario, int maxTokens, double temperatura) {
        if (MODO_NUVEM) {
            PromptIARequestGoogle g = new PromptIARequestGoogle(sistema);
            return g.gerarResposta(usuario);
        }
        try {
            return LlamaCliManager.perguntar(sistema, usuario);
        } catch (Exception e) {
            System.out.println("[ia] " + e.getMessage());
            return null;
        }
    }

    public String resumirPesquisa(String query, String resultados) {
        if (resultados == null || resultados.isBlank()) {
            return null;
        }
        if (MODO_NUVEM) {
            if (googleBot == null) {
                googleBot = new PromptIARequestGoogle(promptSistema);
            }
            return googleBot.resumirPesquisa(query, resultados);
        }
        String texto = resultados.length() > 700 ? resultados.substring(0, 700) : resultados;
        String resp = completar(
                "Eu falo português. Resuma o texto em uma frase curta, retorne em português do brasil. Use só o texto. Sem lista.",
                "Pergunta: " + query + " | Texto: " + texto,
                60,
                0.1);
        if (resp == null) {
            return null;
        }
        String limpo = resp.trim().replaceFirst("(?i)^(resumo|resposta)\\s*:\\s*", "");
        return limpo.isBlank() ? null : limpo;
    }

    public synchronized void substituirUltimoAssistente(String novoTexto) {
        if (historicoConversa.isEmpty() || novoTexto == null) {
            return;
        }
        String ultima = historicoConversa.get(historicoConversa.size() - 1);
        if (ultima.startsWith("assistant:")) {
            historicoConversa.set(historicoConversa.size() - 1, "assistant: " + novoTexto);
        }
    }

    public int getTamanhoHistorico() {
        return historicoConversa.size();
    }

    public synchronized String getResumoHistorico() {
        if (historicoConversa.isEmpty()) {
            return "Histórico vazio";
        }
        StringBuilder resumo = new StringBuilder();
        int maxIndex = Math.min(historicoConversa.size(), 5);
        for (int i = 0; i < maxIndex; i++) {
            String msg = historicoConversa.get(i);
            String preview = msg.length() > 40 ? msg.substring(0, 40) + "..." : msg;
            resumo.append(preview).append('\n');
        }
        return resumo.toString();
    }

    public static String obterPalavrasMaisFrequentes(String texto, String keywords) {
        if (texto == null || keywords == null) {
            return "";
        }
        String[] palavrasTexto = texto.toLowerCase().split("\\s+");
        String[] palavrasChave = keywords.toLowerCase().split("\\s+");
        Map<String, Integer> contagem = new HashMap<>();
        for (String chave : palavrasChave) {
            int freq = 0;
            for (String p : palavrasTexto) {
                if (p.equals(chave)) freq++;
            }
            contagem.put(chave, freq);
        }
        if (contagem.isEmpty()) return "";
        int max = Collections.max(contagem.values());
        if (max == 0) return String.join(" ", palavrasChave);
        
        return contagem.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(" "));
    }

    public synchronized String gerarResumoConversa() {
        if (historicoConversa.isEmpty()) {
            return "Nenhuma conversa para resumir.";
        }
        if (MODO_NUVEM) {
            if (googleBot == null) {
                googleBot = new PromptIARequestGoogle(promptSistema);
            }
            return googleBot.gerarResumoConversa();
        }
        try {
            int start = Math.max(0, historicoConversa.size() - 4);
            StringBuilder chathistory = new StringBuilder();
            for (int i = start; i < historicoConversa.size(); i++) {
                chathistory.append(historicoConversa.get(i)).append(" | ");
            }
            
            String resumo = completar(
                    "Resuma em até 3 frases curtas, em português.", 
                    chathistory.toString(), 
                    80, 0.2);
            
            String data = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            return "Resumo da conversa (" + data + "):\n" + (resumo == null ? "" : resumo.trim());
        } catch (Exception e) {
            return "Erro ao gerar resumo: " + e.getMessage();
        }
    }

    public synchronized void iniciarNovaConversaComResumo(String resumo) {
        if (resumo == null || resumo.isBlank()) return;
        historicoConversa.add("system: Contexto anterior: " + resumo.trim());
        if (googleBot != null) {
            googleBot.iniciarNovaConversaComResumo(resumo);
        }
    }

    private void removerUltimoUsuario() {
        if (historicoConversa.isEmpty()) return;
        String ultimo = historicoConversa.get(historicoConversa.size() - 1);
        if (ultimo.startsWith("user:")) {
            historicoConversa.remove(historicoConversa.size() - 1);
        }
    }
}
