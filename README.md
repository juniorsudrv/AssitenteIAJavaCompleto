# 🤖 Assistente com Visão

Assistente virtual em Java que combina **visão computacional**, **reconhecimento facial**, **voz (fala e escuta)** e **inteligência artificial** — funcionando tanto com **IA local (Llama CLI)** quanto com **IA na nuvem (Google Gemini)**.

O sistema reconhece quem está na frente da câmera, conversa por voz em português, executa comandos, faz pesquisas na web/Wikipedia, cria arquivos e mantém memória por usuário.

---

## ✨ Funcionalidades

### 👁️ Visão Computacional
- Detecção de rostos em tempo real via **OpenCV** (Haar Cascade)
- **Reconhecimento facial** com embeddings extraídos por rede neural **ONNX (ArcFace ResNet100)**
- Cadastro automático de novos usuários: a IA pergunta o nome em voz alta e salva o rosto no HD
- Troca automática de contexto ao reconhecer um usuário já cadastrado

### 🎙️ Voz
- **Escuta contínua** com detecção de fala por volume (VAD simples) e pré-roll de áudio
- Transcrição em português com **Whisper (whisper.cpp)**
- Síntese de voz natural com **Piper TTS** (voz `pt_BR-cadu-medium`)
- Palavras de ativação: *assistente*, *computador*, *sistema*

### 🧠 Inteligência Artificial (dois modos)
| Modo | Engine | Requisitos |
|------|--------|-----------|
| **Local** | Llama CLI (GGUF, roda na GPU/CPU) | Arquivo `.gguf` + `llama-cli.exe` |
| **Nuvem** | Google Gemini (API OpenAI-compatible) | Chave de API do Google |

### 🛠️ Ações executáveis
A IA responde sempre com um **prefixo de comando** que o sistema interpreta:

| Prefixo | Ação |
|---------|------|
| `PESQUISA:` | Pesquisa na web (Bing RSS → Wikipédia) e fala o resumo |
| `PESQUISA_E_ARQUIVO:` | Pesquisa e salva o resumo em `.txt` na Área de Trabalho |
| `CMD:` | Executa comandos do sistema (abrir sites, apps etc.) |
| `ARQUIVO:` | Cria arquivo com conteúdo ditado |
| `TXT:` | Apenas conversa (bate-papo) |

### 💾 Memória
- **Cache global de pesquisas** (`cache_pesquisas.txt`) — evita consultas repetidas
- **Perfil por usuário** (`perfil_<nome>.txt`) — guarda última pesquisa, preferências etc.
- **Rostos cadastrados** (`rostos_cadastrados.txt`) — embeddings salvos em disco

### 🪟 Interface
- **Janela flutuante** (Swing) com transparência, sempre no topo, exibe nome do usuário, status e resposta
- Fecha automaticamente após o tempo de fala terminar

---

## 📁 Estrutura do Projeto

```
AssistenteComVisao/
├── src/main/java/
│   ├── assistente/
│   │   ├── ia/
│   │   │   ├── ConsultaIA.java              # Fachada que alterna entre IA local e nuvem
│   │   │   ├── LlamaCliManager.java         # Gerencia o processo llama-cli.exe (IA local)
│   │   │   ├── PromptIARequestGoogle.java   # Cliente da API Gemini
│   │   │   └── PesquisaWikipedia.java       # Busca web (Bing RSS) + Wikipédia
│   │   ├── pri/
│   │   │   └── AssistenteComVisao.java      # Classe principal (main)
│   │   ├── ui/
│   │   │   └── JanelaFlutuante.java         # Janela Swing flutuante
│   │   └── voz/
│   │       ├── MicrofoneInteligente.java    # Captura de áudio + Whisper
│   │       ├── PiperTTS.java                # Síntese de voz
│   │       └── SistemaOperacional.java      # Detecção de SO
│
└── src/main/resources/
    ├── llama/          → llama-cli.exe (binário)
    ├── model/          → modelos .gguf, .onnx, .bin, haarcascade
    ├── piper/          → piper.exe + espeak-ng-data + voz .onnx
    └── whisper/        → whisper-cli.exe
```

---

## ⚙️ Requisitos

- **Java 17+**
- **OpenCV** (carregado via `nu.pattern.OpenCV`)
- **ONNX Runtime** (`ai.onnxruntime`)
- **JSON-java** (`org.json`)
- **Windows** (caminhos dos binários são otimizados para Windows; Linux tem suporte parcial via `SistemaOperacional.ehWindows()`)

### Arquivos que você precisa baixar e colocar em `resources/`

| Pasta | Arquivo | Onde conseguir |
|-------|---------|----------------|
| `model/` | `*.gguf` (modelo Llama) | [Hugging Face – TheBloke](https://huggingface.co/TheBloke) |
| `model/` | `arcfaceresnet100-11-int8.onnx` | [ONNX Model Zoo](https://github.com/onnx/models) |
| `model/` | `haarcascade_frontalface_default.xml` | [OpenCV GitHub](https://github.com/opencv/opencv/tree/master/data/haarcascades) |
| `model/` | `ggml-base.bin` (Whisper) | [whisper.cpp](https://github.com/ggerganov/whisper.cpp) |
| `model/` | `pt_BR-cadu-medium.onnx` (Piper) | [Piper Voices](https://huggingface.co/rhasspy/piper-voices) |
| `llama/` | `llama-cli.exe` | [llama.cpp releases](https://github.com/ggerganov/llama.cpp/releases) |
| `whisper/` | `whisper-cli.exe` | [whisper.cpp releases](https://github.com/ggerganov/whisper.cpp/releases) |
| `piper/` | `piper.exe` + `espeak-ng-data/` | [Piper releases](https://github.com/rhasspy/piper/releases) |

> O `LlamaCliManager` procura automaticamente por `.gguf` nas pastas `models/`, `src/main/resources/model/` e na raiz. Se houver mais de um, você escolhe pelo terminal.

---

## 🚀 Como Executar

1. Clone o repositório:
   ```bash
   git clone https://github.com/<seu-usuario>/AssistenteComVisao.git
   cd AssistenteComVisao
   ```

2. Coloque todos os arquivos listados acima nas pastas corretas dentro de `src/main/resources/`.

3. Configure sua chave da API Google Gemini em `PromptIARequestGoogle.java` (apenas se for usar o modo Nuvem):
   ```java
   private String GOOGLE_API_KEY = "SUA_CHAVE_AQUI";
   ```

4. Compile e rode:
   ```bash
   mvn clean package
   java -cp target/classes assistente.pri.AssistenteComVisao
   ```
   > Ou execute diretamente pela sua IDE (IntelliJ / Eclipse / NetBeans), rodando `AssistenteComVisao.main()`.

5. No console você escolherá:
   - Modo da IA: **1 = Local (Llama)** ou **2 = Nuvem (Gemini)**
   - Câmera (0, 1, 2...)
   - Microfone

6. Na câmera, se aparecer um rosto desconhecido, a IA pergunta: *"Quem é você?"* — diga seu nome em voz alta.

---

## 🗣️ Exemplos de Uso

| Você diz | IA responde (internamente) | O que acontece |
|----------|---------------------------|----------------|
| "Assistente, quem foi Albert Einstein?" | `PESQUISA: Albert Einstein` | Pesquisa e fala o resumo |
| "Assistente, pesquise sobre o sol e salve num arquivo" | `PESQUISA_E_ARQUIVO: Sol` | Pesquisa, resume e cria `Sol.txt` na Área de Trabalho |
| "Assistente, abre o YouTube" | `CMD: cmd /c start https://www.youtube.com` | Abre o navegador |
| "Assistente, cria um arquivo compras.txt com maçã e pão" | `ARQUIVO: compras.txt \| maçã e pão` | Cria o arquivo na Área de Trabalho |
| "Assistente, bom dia!" | `TXT: Bom dia! Como posso ajudar?` | Apenas conversa |

**Palavras de ativação:** diga *"assistente"*, *"computador"* ou *"sistema"* antes do comando. Ou apenas comece a falar logo após a saudação (janela de 15s).

---

## 🧩 Arquitetura Resumida

```
┌──────────────────┐      ┌─────────────────────┐
│   Microfone      │─────▶│  Whisper (STT)      │
└──────────────────┘      └──────────┬──────────┘
                                     │ texto
                                     ▼
┌──────────────────┐      ┌─────────────────────┐
│   Câmera +       │      │   ConsultaIA        │
│   OpenCV + ONNX  │─────▶│  ┌──────────────┐   │
│  (reconhecimento)│      │  │ Llama CLI    │   │
└──────────────────┘      │  │  ou          │   │
                          │  │ Gemini API   │   │
                          │  └──────────────┘   │
                          └──────────┬──────────┘
                                     │ prefixo (PESQUISA:, CMD:...)
                                     ▼
                          ┌─────────────────────┐
                          │  Ação executada     │
                          │  (arquivo/cmd/web)  │
                          └──────────┬──────────┘
                                     │ resposta
                                     ▼
                          ┌─────────────────────┐
                          │  Piper TTS +        │
                          │  Janela Flutuante   │
                          └─────────────────────┘
```

---

## 🔐 Privacidade

- No **modo Local**, nada sai da sua máquina: o modelo, o reconhecimento facial e a transcrição rodam 100% offline.
- No **modo Nuvem**, apenas o texto do seu comando é enviado para a API do Google Gemini. Áudio, vídeo e embeddings **nunca** são enviados.

---

## ⚠️ Avisos

- A chave da API do Google está **em texto puro** no código. Para produção, use variáveis de ambiente ou um arquivo `.env`.
- O reconhecimento facial é **local** e **não substitui** autenticação segura — use apenas como conveniência.
- Modelos GGUF grandes exigem bastante RAM/VRAM. Para placas como **NVIDIA 920M (1–2 GB)**, prefira modelos quantizados (Q4_K_M ou menores) e `-ngl` baixo.

---

## 🛠️ Tecnologias

- **Java 17** + Swing
- **OpenCV** — visão computacional
- **ONNX Runtime** — inferência do ArcFace
- **llama.cpp** — IA local via CLI
- **Google Gemini API** — IA na nuvem
- **whisper.cpp** — transcrição de voz
- **Piper** — síntese de voz neural
- **org.json** — parsing de JSON

---

## 📜 Licença

Este projeto é de uso pessoal/educacional. Verifique as licenças individuais de cada modelo e binário utilizado (Llama, Whisper, Piper, ArcFace, OpenCV).

---

## 🤝 Contribuindo

Pull requests são bem-vindos! Para mudanças grandes, abra uma issue primeiro para discutirmos o que você gostaria de alterar.

---

**Feito com alegria e muita curiosidade.**