# MotorZoom

Aplicativo Android para gravar vídeos e fotos em 4:3 com rocker de zoom contínuo
de filmadora e processamento NTSC-RS totalmente offline.

## Câmera

- Segure **T** ou **W** para controlar o zoom motorizado.
- Ajuste a velocidade entre `0,10×/s` e `1,50×/s`.
- Grave vídeos e tire fotos em 4:3 com a câmera traseira.
- O rocker ao vivo envia continuamente a posição mais recente à câmera,
  ajustado para o Galaxy A06.

## Processamento offline

- Importa presets `.json` do NTSC-RS para PC.
- Usa o núcleo oficial `ntsc-rs` nativamente, sem navegador ou internet.
- Editor de rocker em pós: reproduza um vídeo 60 fps, segure **T/W** quantas
  vezes quiser e use o movimento gravado no render.
- Correção opcional de temperatura, saturação, contraste, brilho e matiz.
- Fish-eye de lente opcional com distorção de barril, borda arredondada,
  vinheta e leve aberração cromática nas extremidades.
- CCD Vertical Smear opcional com detecção restrita a luzes saturadas, faixa
  fina de registro vertical e cor automática baseada na própria luz, aplicado
  antes do NTSC-RS.
- Galeria offline integrada para fotos, MP4 e MPG do MotorZoom; arquivos MPG 480i recebem uma prévia MP4 temporária sem alterar o original.
- Processamento de vídeo em segundo plano com progresso na notificação; a câmera e a tela podem continuar sendo usadas durante o render.
- A prévia de MPG usa bob deinterlacing a 59,94 fps para preservar visualmente o movimento dos campos 480i.
- Data e horário opcionais no estilo de filmadora, degradados pelo próprio NTSC-RS.
- Escolha explícita de saída: MP4 compatível para qualquer FPS (padrão) ou
  MPEG-2 NTSC 480i verdadeiro apenas para fontes 59,94/60 fps. O app detecta
  fontes de 30 fps e desativa o 480i antes do processamento.

O modo 480i gera 720×480, proporção 4:3, 29,97 quadros e 59,94 campos temporais
por segundo. Ele exige uma fonte 59,94/60 fps para que os dois campos representem
instantes diferentes. Os arquivos são salvos em `Movies/MotorZoom`.

## Compilar pelo GitHub

Abra **Actions → Compilar APK → Run workflow**. O workflow compila o núcleo Rust,
o exportador FFmpeg ARM64 e o APK, disponibilizado no artefato
`MotorZoom-debug-apk`.

O APK atual é destinado a celulares Android ARM64 com Android 8 ou superior. O
Galaxy A06 é o aparelho principal de teste.
