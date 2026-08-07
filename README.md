# Motor Zoom

Aplicativo Android minimalista para gravar vídeo e tirar fotos em 4:3, com zoom contínuo no estilo de uma filmadora.

## Controles

- Segure **T** para aproximar.
- Segure **W** para afastar.
- Solte o botão para interromper imediatamente.
- Ajuste a velocidade entre `0,10×/s` e `1,50×/s`.
- Toque em **REC** para gravar; toque em **STOP** para finalizar.
- Toque em **FOTO** para salvar uma fotografia JPEG em 4:3.
- Toque em **NTSC-RS** para abrir o editor oficial completo em uma aba integrada do Chrome.
- Os vídeos são salvos em `Movies/MotorZoom`.
- As fotos são salvas em `Pictures/MotorZoom`.

A prévia usa uma janela 4:3 central e o mesmo enquadramento é solicitado para foto e vídeo. As fotos são salvas em 4:3. No vídeo, o CameraX aplica o viewport 4:3 compartilhado, mas a resolução/container exatos ainda dependem dos perfis de gravação expostos pelo Galaxy A06; isso deve ser confirmado no primeiro APK executado no aparelho.

O zoom fica limitado a `1×–4×` e usa apenas a câmera traseira principal. Isso evita tentar atravessar lentes e reduz saltos visíveis no Galaxy A06.

## Compilar no Android Studio

1. Instale o Android Studio com o Android SDK 35.
2. Abra esta pasta como projeto.
3. Aguarde a sincronização do Gradle.
4. Conecte o Galaxy A06 com a depuração USB ativa.
5. Use **Run > Run 'app'**.

Para gerar um APK instalável, use **Build > Build APK(s)**. O arquivo aparecerá em `app/build/outputs/apk/debug/app-debug.apk`.

## Compilar sem Android Studio, pelo GitHub

1. Crie um repositório vazio no GitHub.
2. Envie o conteúdo desta pasta, incluindo a pasta `.github`.
3. Abra a aba **Actions** do repositório.
4. Selecione **Compilar APK** e aguarde a execução terminar.
5. Abra a execução concluída e baixe o artefato **MotorZoom-debug-apk**.
6. Extraia o ZIP do artefato e instale `app-debug.apk` no celular.

O workflow também começa automaticamente quando o projeto é enviado para a branch `main` ou `master`.

## Editor NTSC-RS

O botão **NTSC-RS** abre a build web oficial empacotada no APK em uma Chrome Custom Tab, servida apenas no endereço local `127.0.0.1`. Essa abordagem mantém WebAssembly e WebCodecs para importar mídia, ajustar o efeito, trabalhar com presets e exportar o resultado, sem conexão externa durante o uso.

O workflow baixa uma cópia fixa da versão web oficial e suas licenças antes de compilar. Se o módulo WebAssembly não for encontrado, a compilação falha em vez de produzir um APK offline incompleto. Por segurança do Android, o navegador exige que o usuário selecione manualmente o vídeo ou a foto que será processado.

O editor funciona sem internet desde a primeira execução do APK. Como a cópia fica congelada na versão baixada durante a compilação, uma atualização futura do editor exige gerar um novo APK.

Enquanto o editor está aberto, uma notificação discreta mantém o servidor local ativo. Ela desaparece quando o usuário retorna ao Motor Zoom. O controle de zoom agrupa comandos pendentes e envia apenas o valor mais recente ao CameraX, reduzindo cancelamentos e engasgos no Galaxy A06.

## Observações do Galaxy A06

O aparelho não possui teleobjetiva. Portanto, o zoom acima de 1× é um recorte digital do sensor principal. Para preservar mais detalhe antes do processamento no `ntsc-rs`, grave com boa iluminação e mantenha o zoom moderado, preferencialmente até 2×.

Este protótipo prioriza 1080p e recua para 720p se a combinação de vídeo, foto e prévia não for aceita pelo aparelho.
# Motor Zoom

Aplicativo Android para câmera 4:3 com rocker de zoom contínuo e processamento
NTSC-RS pós-gravação totalmente offline.

## NTSC-RS nativo

- Escolha **PROCESSAR** e importe presets `.json` criados no NTSC-RS do PC.
- O núcleo oficial `ntsc-rs` roda dentro do APK por JNI; não usa navegador,
  servidor local ou internet.
- O resultado é gerado em 640×480 em `Movies/MotorZoom`, sem alterar o original.
- A faixa de áudio original é copiada sem recompressão para preservar a qualidade.
- Nesta versão, o processador é destinado aos vídeos 4:3 horizontais gravados pelo app.

## Compilar

Envie o projeto ao GitHub e execute **Actions → Compilar APK → Run workflow**.
O workflow compila o núcleo Rust ARM64 e disponibiliza `MotorZoom-debug-apk`.
