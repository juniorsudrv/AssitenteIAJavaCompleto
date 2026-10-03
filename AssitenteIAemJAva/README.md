# Assistente com visão

Aplicação local: câmera com reconhecimento facial, microfone (Whisper), voz (Piper) e um modelo de linguagem via `llama-server`.

A classe principal é `cn.ck.AssistenteComVisao`. Execute a partir da raiz deste projeto, porque câmera, voz e o servidor leem caminhos relativos a ela.

```text
mvn -q compile exec:java
```

## O que fica no projeto

- `src/main/java/cn/ck/AssistenteComVisao.java` — câmera, rosto e orquestração
- `src/main/java/cn/ck/ia` — consulta ao modelo, `llama-server` e Wikipedia
- `src/main/java/cn/ck/voz` — microfone, Piper e detecção do sistema
- `src/main/java/cn/ck/ui` — janela flutuante
- `src/main/resources/model` — ArcFace, Haar cascade, voz `pt_BR-cadu` e Whisper tiny
- `src/main/resources/llama` — somente o `llama-server` e as DLLs que ele carrega
- `src/main/resources/piper` e `src/main/resources/whisper` — executáveis de fala e transcrição
- `models/` — arquivos `.gguf`. O servidor lista essa pasta na inicialização

Rostos, perfis e o cache de pesquisa são gravados na raiz do projeto (`rostos_cadastrados.txt`, `perfil_*.txt`, `cache_pesquisas.txt`).

JDK 11 ou superior e Maven. No Windows a câmera usa a DLL `opencv_videoio_ffmpeg470_64.dll` que já está em `src/main/resources/lib`.
