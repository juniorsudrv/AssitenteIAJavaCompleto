package assistente.ia;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PesquisaWikipedia {

    private static final String USER_AGENT = "AssistenteComVisao/1.0 (uso local)";
    private static final String NAVEGADOR = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final Pattern ENTIDADE = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    public static String pesquisarNaWeb(String termo) {
        if (termo == null || termo.isBlank()) {
            return "FALHA: Sobre o que você quer pesquisar?";
        }
        try {
            String url = "https://www.bing.com/search?format=rss&setlang=pt-br&cc=BR&q="
                    + URLEncoder.encode(termo, StandardCharsets.UTF_8);
            String rss = baixar(url, NAVEGADOR, "application/rss+xml", 8000);
            String trechos = extrairRss(rss);
            if (trechos != null && !trechos.isBlank()) {
                return trechos;
            }
        } catch (Exception e) {
            System.err.println("[web] " + e.getMessage());
        }
        String wiki = pesquisar(termo);
        if (wiki != null && !wiki.startsWith("FALHA:")) {
            return wiki;
        }
        return "FALHA: Não encontrei nada na web sobre " + termo + ".";
    }

    public static String pesquisar(String termo) {
        if (termo == null || termo.isBlank()) {
            return "FALHA: Sobre o que você quer pesquisar?";
        }
        try {
            String titulo = null;
            String snippet = null;
            String busca = buscar(termo);
            if (busca != null) {
                titulo = busca;
            }
            int separador = busca == null ? -1 : busca.indexOf('\n');
            if (separador >= 0) {
                titulo = busca.substring(0, separador);
                snippet = busca.substring(separador + 1);
            }
            if (titulo == null || titulo.isBlank()) {
                return "FALHA: Não encontrei nada na Wikipedia sobre " + termo + ".";
            }

            String resumo = obterResumo(titulo);
            if (resumo != null && !resumo.isBlank()) {
                return resumo;
            }
            if (snippet != null && !snippet.isBlank()) {
                return snippet;
            }
            return "FALHA: A Wikipedia não tem um resumo sobre " + titulo + ".";
        } catch (Exception e) {
            System.err.println("[wiki] " + e.getMessage());
            return "FALHA: Não consegui consultar a Wikipedia.";
        }
    }

    public static String primeirasFrases(String texto, int quantidade) {
        if (texto == null) {
            return "";
        }
        String limpo = texto.replaceAll("\\s+", " ").trim();
        if (limpo.startsWith("FALHA:")) {
            limpo = limpo.substring(6).trim();
        }
        if (limpo.isEmpty() || quantidade < 1) {
            return "";
        }
        String[] partes = limpo.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String parte : partes) {
            if (parte.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(parte.trim());
            n++;
            if (n >= quantidade || sb.length() > 280) {
                break;
            }
        }
        String frase = sb.toString().trim();
        if (frase.length() > 280) {
            int corte = frase.lastIndexOf(' ', 280);
            frase = frase.substring(0, corte > 40 ? corte : 280).trim();
        }
        return frase;
    }

    private static String buscar(String termo) throws Exception {
        String url = "https://pt.wikipedia.org/w/api.php"
                + "?action=query"
                + "&list=search"
                + "&srsearch=" + URLEncoder.encode(termo, StandardCharsets.UTF_8)
                + "&srlimit=1"
                + "&format=json";

        String json = baixar(url, USER_AGENT, "application/json", 6000);
        if (json == null || json.isBlank()) {
            return null;
        }
        JSONObject obj = new JSONObject(json);
        JSONArray resultados = obj.getJSONObject("query").getJSONArray("search");
        if (resultados.length() == 0) {
            return null;
        }
        JSONObject primeiro = resultados.getJSONObject(0);
        String titulo = primeiro.getString("title");
        String snippet = limparHtml(primeiro.optString("snippet", ""));
        if (snippet.isBlank()) {
            return titulo;
        }
        return titulo + "\n" + snippet;
    }

    private static String obterResumo(String titulo) throws Exception {
        String tituloCodificado = URLEncoder.encode(titulo.replace(" ", "_"), StandardCharsets.UTF_8);
        String url = "https://pt.wikipedia.org/api/rest_v1/page/summary/" + tituloCodificado;
        String json = baixar(url, USER_AGENT, "application/json", 6000);
        if (json == null || json.isBlank()) {
            return null;
        }
        JSONObject obj = new JSONObject(json);
        if (obj.has("extract") && !obj.isNull("extract")) {
            return obj.getString("extract");
        }
        return null;
    }

    private static String extrairRss(String xml) {
        if (xml == null || !xml.contains("<item>")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        int achados = 0;
        while (achados < 4 && sb.length() < 900) {
            int inicio = xml.indexOf("<item>", cursor);
            int fim = inicio < 0 ? -1 : xml.indexOf("</item>", inicio);
            if (inicio < 0 || fim < 0) {
                break;
            }
            String item = xml.substring(inicio, fim);
            cursor = fim + 7;
            String titulo = tagXml(item, "title");
            String snip = tagXml(item, "description");
            snip = snip.replaceAll("\\[[0-9]+\\]", " ").replaceAll("\\s+", " ").trim();
            snip = snip.replaceAll("(?:\\.\\.\\.|…)+$", "").trim();
            if (snip.length() < 40) {
                continue;
            }
            if (snip.length() > 320) {
                snip = snip.substring(0, 320).trim();
            }
            if (titulo.length() > 120) {
                titulo = titulo.substring(0, 120).trim();
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (!titulo.isBlank()) {
                sb.append(titulo).append(". ");
            }
            sb.append(snip);
            achados++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String tagXml(String item, String nome) {
        String abre = "<" + nome + ">";
        String fecha = "</" + nome + ">";
        int inicio = item.indexOf(abre);
        int fim = inicio < 0 ? -1 : item.indexOf(fecha, inicio);
        if (inicio < 0 || fim < 0) {
            return "";
        }
        String valor = item.substring(inicio + abre.length(), fim).trim();
        if (valor.startsWith("<![CDATA[")) {
            valor = valor.substring(9);
            if (valor.endsWith("]]>")) {
                valor = valor.substring(0, valor.length() - 3);
            }
        }
        return limparHtml(valor);
    }

    private static String limparHtml(String html) {
        if (html == null) {
            return "";
        }
        String texto = html.replaceAll("<[^>]+>", " ")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
        Matcher entidades = ENTIDADE.matcher(texto);
        StringBuffer sb = new StringBuffer();
        while (entidades.find()) {
            String numero = entidades.group(1);
            int codigo;
            try {
                if (numero.charAt(0) == 'x' || numero.charAt(0) == 'X') {
                    codigo = Integer.parseInt(numero.substring(1), 16);
                } else {
                    codigo = Integer.parseInt(numero);
                }
            } catch (NumberFormatException e) {
                entidades.appendReplacement(sb, Matcher.quoteReplacement(entidades.group()));
                continue;
            }
            if (codigo < 32 || codigo > 0x10FFFF || (codigo >= 0xD800 && codigo <= 0xDFFF)) {
                entidades.appendReplacement(sb, " ");
                continue;
            }
            entidades.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(codigo))));
        }
        entidades.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String baixar(String urlStr, String agente, String accept, int leituraMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", agente);
            conn.setRequestProperty("Accept", accept);
            conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(leituraMs);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                System.out.println("[web] código " + code);
                return null;
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String linha;
                while ((linha = br.readLine()) != null) {
                    sb.append(linha).append('\n');
                }
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }
    
    
    public static void main(String[] args) {
        System.out.println(PesquisaWikipedia.pesquisar("pesquisa pra mim a palavra cogumelo"));
    }
}
