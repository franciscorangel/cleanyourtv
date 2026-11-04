# Sony BRAVIA KD-65X755F (Brasil)

Lista em [`debloat.txt`](debloat.txt): o mesmo build S54 de [`sony-kd-55xf8096`](../sony-kd-55xf8096/), aplicado
em dois lotes com alguns dias de uso entre eles. Nada quebrou; TV aberta (ISDB-T), interatividade, HDMI-CEC,
Chromecast, Assistente, Modo ambiente, temporizador e controle por IP continuam funcionando.

Extras deste fork, opcionais e testados nesta TV:

- [`cinema/`](../../cinema/README.md): uma Home nova (Continuar assistindo de todos os apps, uma linha por
  streaming, grade de Apps, Ajustes num painel lateral);
- [`tvkeys/`](../../tvkeys/README.md): os botões coloridos e o botão Apps do controle abrindo apps, sem root;
- [`tvprobe/`](../../tvprobe/README.md): diagnóstico do que os apps publicam para a Home.

O temporizador de desligar da Sony não é exposto para apps; a Cinema abre a tela dele
(`com.sony.dtv.timers`) pelo botão Desligar.
