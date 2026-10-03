package assistente.voz;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class PiperTTS {

    public static volatile boolean estaFalando = false;
    private static final AtomicInteger pendentes = new AtomicInteger();
    private static volatile long silencioAposFala;
    private static final float AMOSTRA_HZ = 22050f;
    private final String piperExecutablePath;
    private final String modelPath;

    private final BlockingQueue<String> filaTextos = new LinkedBlockingQueue<>();

    public PiperTTS(String piperExecutablePath, String modelPath) {
        this.piperExecutablePath = piperExecutablePath;
        this.modelPath = modelPath;
        iniciarSintese();
    }

    private void iniciarSintese() {
        Thread threadSintese = new Thread(() -> {
            while (true) {
                boolean pegou = false;
                Process processo = null;
                SourceDataLine linha = null;
                try {
                    String texto = filaTextos.take();
                    pegou = true;
                    File espeak = new File(new File(piperExecutablePath).getParentFile(), "espeak-ng-data");
                    ProcessBuilder pb = new ProcessBuilder(
                            piperExecutablePath,
                            "--model", modelPath,
                            "--output_raw",
                            "--quiet",
                            "--noise_scale", "0.3",
                            "--noise_w", "0.4",
                            "--length_scale", "0.90",
                            "--sentence_silence", "0.08",
                            "--espeak_data", espeak.getAbsolutePath()
                    );
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                    processo = pb.start();
                    try (BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(processo.getOutputStream(), StandardCharsets.UTF_8))) {
                        writer.write(texto);
                    }

                    AudioFormat formato = new AudioFormat(AMOSTRA_HZ, 16, 1, true, false);
                    linha = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, formato));
                    linha.open(formato, 8192);
                    linha.start();
                    estaFalando = true;

                    byte[] bloco = new byte[4096];
                    long inicioFala = 0;
                    int bytesEscritos = 0;
                    try (InputStream audio = processo.getInputStream()) {
                        int lidos;
                        while ((lidos = audio.read(bloco)) != -1) {
                            if ((lidos & 1) == 1) {
                                lidos--;
                            }
                            if (lidos > 0) {
                                if (inicioFala == 0) {
                                    inicioFala = System.currentTimeMillis();
                                }
                                linha.write(bloco, 0, lidos);
                                bytesEscritos += lidos;
                            }
                        }
                    }
                    int exitCode = processo.waitFor();
                    if (exitCode != 0) {
                        System.err.println("Erro na síntese. Código Piper: " + exitCode);
                    }
                    if (bytesEscritos > 0 && inicioFala > 0) {
                        long duracaoMs = Math.round(bytesEscritos * 1000.0 / (AMOSTRA_HZ * 2.0));
                        long espera = inicioFala + duracaoMs - System.currentTimeMillis();
                        if (espera > 0) {
                            Thread.sleep(espera);
                        }
                    }
                    linha.drain();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    estaFalando = false;
                    if (linha != null) {
                        linha.stop();
                        linha.close();
                    }
                    if (processo != null && processo.isAlive()) {
                        processo.destroyForcibly();
                    }
                    if (pegou) {
                        pendentes.decrementAndGet();
                        silencioAposFala = System.currentTimeMillis() + 1200;
                    }
                }
            }
        }, "piper");
        threadSintese.setDaemon(true);
        threadSintese.start();
    }

    public void falar(String textoCompleto) {
        if (textoCompleto == null || textoCompleto.trim().isEmpty()) {
            return;
        }
        pendentes.incrementAndGet();
        filaTextos.offer(textoCompleto.trim());
    }

    static PiperTTS tts;

    public static void st_falar(String texto) {
        if (tts == null) {
            String executavel = SistemaOperacional.ehWindows()
                    ? "src/main/resources/piper/piper.exe"
                    : "src/main/resources/piper_linux/piper";
            String modelo = "src/main/resources/model/pt_BR-cadu-medium.onnx";
            tts = new PiperTTS(executavel, modelo);
        }
        tts.falar(texto);
    }

    public static boolean temTextoPendente() {
        if (System.currentTimeMillis() < silencioAposFala) {
            return true;
        }
        if (estaFalando || pendentes.get() > 0) {
            return true;
        }
        if (tts == null) {
            return false;
        }
        return !tts.filaTextos.isEmpty();
    }
}
