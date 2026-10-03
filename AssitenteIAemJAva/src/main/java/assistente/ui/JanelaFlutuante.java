package assistente.ui;

import assistente.voz.MicrofoneInteligente;
import assistente.voz.PiperTTS;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

public class JanelaFlutuante {

    private static final int LARGURA = 420;
    private static final int ALTURA = 286;

    private static final Color FUNDO = new Color(22, 24, 28, 236);
    private static final Color FUNDO_OPACO = new Color(22, 24, 28);
    private static final Color TITULO = new Color(126, 168, 255);
    private static final Color STATUS = new Color(168, 176, 186);
    private static final Color MEMORIA = new Color(130, 196, 164);
    private static final Color TEXTO = new Color(244, 246, 248);
    private static final Color CONHECIDO = new Color(80, 200, 140);
    private static final Color NEUTRO = new Color(90, 98, 110);

    private static JWindow janela;
    private static JPanel raiz;
    private static JLabel rotuloNome;
    private static JLabel rotuloStatus;
    private static JLabel rotuloMemoria;
    private static JTextArea area;
    private static Timer vigilante;
    private static boolean translucido = true;
    private static boolean segurar;
    private static long esconderEm;
    private static Color corAccent = NEUTRO;
    
    private static String pessoaAtual = "";
    private static String ultimoConhecido = ""; // Guarda o último rosto reconhecido
    private static String statusAtual = "Pronto";
    private static String mensagemAtual = "";
    private static String memoriaAtual = "";

    private JanelaFlutuante() {
    }

    public static void definirPresenca(boolean temRosto, String ultimoRosto) {
        SwingUtilities.invokeLater(() -> {
            garantir();
            
            // Salva apenas nomes reais validados (Ignora valores de loading/estado inicial)
            if (ultimoRosto != null && !ultimoRosto.isBlank() && !ultimoRosto.equals("Desconhecido") && !ultimoRosto.equals("Detectando...")) {
                ultimoConhecido = ultimoRosto;
            }

            if (!temRosto) {
                // Ao invés de "Ninguém na Câmera", exibe que a IA está voltada para o último usuário ou buscando
                pessoaAtual = ultimoConhecido.isEmpty() ? "Buscando usuário..." : "Assistente para " + ultimoConhecido;
            } else {
                pessoaAtual = (ultimoRosto != null && !ultimoRosto.isBlank()) ? ultimoRosto : "Detectando...";
            }
            if (janela != null && janela.isVisible()) {
                pintar();
            }
        });
    }

    public static void mostrar(String titulo, String texto) {
        atualizar(null, titulo, texto, null, 20000, false);
    }

    public static void atualizar(String pessoa, String status, String mensagem, String memoria,
                                 int duracaoMs, boolean segurarAteProxima) {
        SwingUtilities.invokeLater(() -> {
            garantir();
            if (pessoa != null) {
                pessoaAtual = pessoa;
            }
            if (status != null) {
                statusAtual = status;
            }
            if (mensagem != null) {
                mensagemAtual = mensagem;
            }
            if (memoria != null) {
                memoriaAtual = memoria;
            }
            segurar = segurarAteProxima;
            esconderEm = System.currentTimeMillis() + Math.max(5000, duracaoMs);
            pintar();
            posicionar();
            janela.setVisible(true);
        });
    }

    public static void fechar() {
        SwingUtilities.invokeLater(() -> {
            segurar = false;
            if (janela != null) {
                janela.setVisible(false);
            }
        });
    }

    private static void garantir() {
        if (janela != null) {
            return;
        }
        GraphicsEnvironment ambiente = GraphicsEnvironment.getLocalGraphicsEnvironment();
        translucido = ambiente.getDefaultScreenDevice().isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);

        janela = new JWindow();
        janela.setAlwaysOnTop(true);
        janela.setFocusableWindowState(false);
        if (translucido) {
            janela.setBackground(new Color(0, 0, 0, 0));
        } else {
            janela.setBackground(FUNDO_OPACO);
        }

        raiz = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (translucido) {
                    g2.setColor(FUNDO);
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 22, 22));
                } else {
                    g2.setColor(FUNDO_OPACO);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                g2.setColor(corAccent);
                g2.fillRoundRect(8, 18, 5, getHeight() - 36, 5, 5);
                g2.dispose();
            }
        };
        raiz.setOpaque(!translucido);
        raiz.setBorder(new EmptyBorder(12, 22, 14, 16));

        JLabel marca = new JLabel("ASSISTENTE");
        marca.setFont(new Font("Segoe UI", Font.BOLD, 11));
        marca.setForeground(TITULO);

        JLabel fechar = new JLabel("fechar");
        fechar.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        fechar.setForeground(new Color(168, 176, 186));
        fechar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fechar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                JanelaFlutuante.fechar();
            }
        });

        JPanel topo = new JPanel(new BorderLayout());
        topo.setOpaque(false);
        topo.add(marca, BorderLayout.WEST);
        topo.add(fechar, BorderLayout.EAST);
        alinhar(topo);

        rotuloNome = new JLabel(" ");
        rotuloNome.setFont(new Font("Segoe UI", Font.BOLD, 20));
        rotuloNome.setForeground(Color.WHITE);
        alinhar(rotuloNome);

        rotuloStatus = new JLabel(" ");
        rotuloStatus.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        rotuloStatus.setForeground(STATUS);
        alinhar(rotuloStatus);

        rotuloMemoria = new JLabel(" ");
        rotuloMemoria.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        rotuloMemoria.setForeground(MEMORIA);
        alinhar(rotuloMemoria);

        JPanel norte = new JPanel();
        norte.setLayout(new BoxLayout(norte, BoxLayout.Y_AXIS));
        norte.setOpaque(false);
        norte.add(topo);
        norte.add(Box.createVerticalStrut(6));
        norte.add(rotuloNome);
        norte.add(Box.createVerticalStrut(2));
        norte.add(rotuloStatus);
        norte.add(Box.createVerticalStrut(4));
        norte.add(rotuloMemoria);

        area = new JTextArea() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setForeground(TEXTO);
        area.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        area.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JScrollPane rolagem = new JScrollPane(area);
        rolagem.setBorder(BorderFactory.createEmptyBorder());
        rolagem.setOpaque(false);
        rolagem.getViewport().setOpaque(false);
        rolagem.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rolagem.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        raiz.add(norte, BorderLayout.NORTH);
        raiz.add(rolagem, BorderLayout.CENTER);
        janela.setContentPane(raiz);
        janela.setSize(LARGURA, ALTURA);

        vigilante = new Timer(400, e -> {
            if (janela == null || !janela.isVisible() || segurar) {
                return;
            }
            // SE ESTIVER PROCESSANDO COMANDO OU FALANDO, NUNCA FECHA
            if (PiperTTS.temTextoPendente() || MicrofoneInteligente.processandoComando) {
                esconderEm = System.currentTimeMillis() + 8000;
                return;
            }
            if (System.currentTimeMillis() >= esconderEm) {
                janela.setVisible(false);
            }
        });
        vigilante.start();
    }

    private static void pintar() {
        if (pessoaAtual.startsWith("Assistente para") || pessoaAtual.equals("Buscando usuário...")) {
            rotuloNome.setText(pessoaAtual);
            rotuloNome.setForeground(new Color(154, 160, 170));
            corAccent = NEUTRO;
        } else {
            rotuloNome.setText(pessoaAtual);
            rotuloNome.setForeground(Color.WHITE);
            corAccent = CONHECIDO;
        }

        rotuloStatus.setText(statusAtual == null || statusAtual.isBlank() ? "Pronto" : statusAtual);
        String memoria = memoriaAtual == null ? "" : memoriaAtual.trim();
        rotuloMemoria.setToolTipText(memoria.isEmpty() ? null : memoria);
        if (memoria.length() > 88) {
            memoria = memoria.substring(0, 87).trim() + "…";
        }
        rotuloMemoria.setText(memoria.isEmpty() ? " " : memoria);

        area.setText(mensagemAtual == null ? "" : mensagemAtual);
        area.setCaretPosition(0);
        raiz.repaint();
    }

    private static void posicionar() {
        Dimension tela = Toolkit.getDefaultToolkit().getScreenSize();
        int x = Math.max(8, tela.width - LARGURA - 18);
        int y = Math.max(8, tela.height - ALTURA - 72);
        janela.setLocation(x, y);
    }

    private static void alinhar(JComponent componente) {
        componente.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    }
}