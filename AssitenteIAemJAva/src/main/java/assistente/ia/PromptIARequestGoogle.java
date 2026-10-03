package assistente.ia;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PromptIARequestGoogle {
    private final String TAG = "TextGeneratorHelper";

    // --- CONFIGURAÇÃO GOOGLE GEMINI (endpoint OpenAI-compatible) ---
    private String GOOGLE_API_KEY = "AQ.Ab8RN6JE8wVIYy6uamsleD_MSXayiqilYwYnTotr-0Q8BkQCJg"; // Substitua pela sua chave completa
    private String API_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private String MODEL_NAME = "gemini-3.8-flash"; // Modelo padrão do Gemini

    private String PROMPT_SISTEMA;
    private List<JSONObject> historicoConversa = new ArrayList<>();
    private final int MAX_HISTORICO = 90;
    public boolean nomeExiste = false;

    public PromptIARequestGoogle(String PROMPT_SISTEMA) {
        this.PROMPT_SISTEMA = PROMPT_SISTEMA;
        limparHistorico();
    }

    public String getPROMPT_SISTEMA() {
        return PROMPT_SISTEMA;
    }

    public void setPROMPT_SISTEMA(String PROMPT_SISTEMA) {
        this.PROMPT_SISTEMA = PROMPT_SISTEMA;
    }

    public void limparHistorico() {
        historicoConversa = new ArrayList<>();
    }

    public void adicionarAoHistorico(String papel, String texto) {
        try {
            JSONObject mensagem = new JSONObject();
            mensagem.put("role", papel);
            mensagem.put("content", texto);
            historicoConversa.add(mensagem);

            // Mantém o histórico dentro do limite máximo (pares de User/Assistant)
            if (historicoConversa.size() > MAX_HISTORICO * 2) {
                historicoConversa = new ArrayList<>(historicoConversa.subList(
                        historicoConversa.size() - MAX_HISTORICO * 2,
                        historicoConversa.size()
                ));
            }
        } catch (Exception e) {
            System.out.println("Erro ao adicionar ao histórico: " + e.getMessage());
        }
    }

    public boolean llmNaoInicializado() {
        return false;
    }

    // --- COMUNICAÇÃO COM A API DO GOOGLE (Gemini - OpenAI compatible) ---
    private String comunicarComAPI() throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + GOOGLE_API_KEY);
        conn.setDoOutput(true);

        // Montagem do corpo do JSON
        JSONObject body = new JSONObject();
        body.put("model", MODEL_NAME);
        body.put("stream", false);
        body.put("temperature", 0.4);

        JSONArray messages = new JSONArray();

        // 1. Mensagem de sistema (Define o comportamento da IA)
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", PROMPT_SISTEMA);
        messages.put(systemMessage);

        // 2. Adiciona o histórico acumulado
        for (JSONObject mensagem : historicoConversa) {
            messages.put(mensagem);
        }

        body.put("messages", messages);

        // Envio da requisição
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        // Leitura da resposta
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            JSONObject jsonResponse = new JSONObject(response.toString());
            return jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        } else {
            // Tratamento de erros
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorResponse.append(line);
            }
            errorReader.close();
            System.out.println("Erro na API Google Gemini (" + responseCode + "): " + errorResponse.toString());
            return null;
        }
    }

    // Gerar resposta simples
    public String gerarResposta(String pergunta) {
        try {
            adicionarAoHistorico("user", pergunta);
            String resp = comunicarComAPI();
            if (resp != null) {
                adicionarAoHistorico("assistant", resp);
                return resp;
            } else {
                return "Erro na conexão com a inteligência artificial.";
            }
        } catch (Exception e) {
            System.out.println("Erro ao gerar resposta online: " + e.getMessage());
            e.printStackTrace();
            return "Erro ao gerar resposta.";
        }
    }

    public String resumirPesquisa(String query, String resultados) {
        if (resultados == null || resultados.isBlank()) {
            return null;
        }
        String texto = resultados.length() > 700 ? resultados.substring(0, 700) : resultados;
        String promptOriginal = this.PROMPT_SISTEMA;
        this.PROMPT_SISTEMA = "Eu falo português. Resuma o texto em uma frase curta, retorne em português do brasil. Use só o texto. Sem lista.";
        String resp = gerarResposta("Pergunta: " + query + " | Texto: " + texto);
        this.PROMPT_SISTEMA = promptOriginal;
        if (resp == null) {
            return null;
        }
        String limpo = resp.trim().replaceFirst("(?i)^(resumo|resposta)\\s*:\\s*", "");
        return limpo.isBlank() ? null : limpo;
    }

    public int getTamanhoHistorico() {
        return historicoConversa.size();
    }

    public String getResumoHistorico() {
        if (historicoConversa.isEmpty()) {
            return "Histórico vazio";
        }
        StringBuilder resumo = new StringBuilder();
        int maxIndex = Math.min(historicoConversa.size(), 5);
        for (int i = 0; i < maxIndex; i++) {
            try {
                JSONObject mensagem = historicoConversa.get(i);
                String role = mensagem.getString("role");
                String text = mensagem.getString("content");
                String preview = text.length() > 30 ? text.substring(0, 30) + "..." : text;
                resumo.append(role).append(": ").append(preview).append("\n");
            } catch (Exception e) {
                System.out.println("Erro ao criar resumo: " + e.getMessage());
            }
        }
        return resumo.toString();
    }

    public static String obterPalavrasMaisFrequentes(String texto, String keywords) {
        if (texto == null || keywords == null) return "";

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

        int max = Collections.max(contagem.values());
        if (max == 0) {
            return String.join(" ", palavrasChave);
        }

        return contagem.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(" "));
    }

    /**
     * Gera um resumo conciso da conversa atual, incluindo a data e hora.
     * O resumo é limitado a poucas frases para não gerar textos excessivos.
     *
     * @return String contendo a data e o resumo da conversa, ou mensagem de erro.
     */
    public String gerarResumoConversa() {
        if (historicoConversa.isEmpty()) {
            return "Nenhuma conversa para resumir.";
        }

        // Prompt que instrui a IA a fazer um resumo breve
        String promptResumo = "Resuma a conversa a seguir em até 4 frases, destacando os principais tópicos e decisões. Seja objetivo e conciso.";

        try {
            // Monta a requisição com as mensagens do histórico (limita às últimas 10 para evitar estouro de tokens)
            JSONArray messages = new JSONArray();
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", promptResumo);
            messages.put(systemMsg);

            int start = Math.max(0, historicoConversa.size() - 10);
            for (int i = start; i < historicoConversa.size(); i++) {
                messages.put(historicoConversa.get(i));
            }

            // Configura a chamada HTTP para a API do Google
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + GOOGLE_API_KEY);
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("model", MODEL_NAME);
            body.put("stream", false);
            body.put("temperature", 0.3); // menor criatividade para respostas diretas
            body.put("messages", messages);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();

                JSONObject jsonResponse = new JSONObject(response.toString());
                String resumo = jsonResponse.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");

                // Adiciona a data atual
                String data = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                return "Resumo da conversa (" + data + "):\n" + resumo;
            } else {
                // Leitura do erro
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                errorReader.close();
                return "Erro ao gerar resumo (código " + responseCode + "): " + errorResponse.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Erro ao gerar resumo: " + e.getMessage();
        }
    }

    public void iniciarNovaConversaComResumo(String resumo) {
        if (resumo == null || resumo.trim().isEmpty()) {
            return;
        }
        // Adiciona o resumo como uma mensagem de sistema no histórico
        JSONObject mensagemResumo = new JSONObject();
        mensagemResumo.put("role", "system");
        mensagemResumo.put("content", "Adicionando mais um contexto para consulta (apenas fatos, sem continuar diálogo) : " + resumo);
        historicoConversa.add(mensagemResumo);
    }

    public static void main(String[] args) {
        PromptIARequestGoogle bot = new PromptIARequestGoogle("Você é um assistente prestativo.");
        System.out.println("Resposta: " + bot.gerarResposta("Olá, quem é você?"));

        System.out.println("\nFrequência: " +
                obterPalavrasMaisFrequentes("Olá como voce esta, Olá", "Olá voce"));
    }
}