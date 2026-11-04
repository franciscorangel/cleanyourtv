package br.casa.tvkeys;

/** Red (scan 398): the app chosen in Cinema (Ajustes → Botões do controle); YouTube without Cinema. */
public final class Vermelho extends Abrir {
    @Override String cor() { return "vermelho"; }
    @Override String alvo() { return "com.google.android.youtube.tv"; }
}
