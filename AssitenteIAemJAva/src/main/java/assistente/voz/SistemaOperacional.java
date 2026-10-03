package assistente.voz;

public class SistemaOperacional {
    
    public static boolean ehWindows() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win");
    }
}
