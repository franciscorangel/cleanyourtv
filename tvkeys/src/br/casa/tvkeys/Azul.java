package br.casa.tvkeys;

/** Blue (scan 401): the app chosen in Cinema (Ajustes → Botões do controle); Stremio without Cinema. */
public final class Azul extends Abrir {
    @Override String cor() { return "azul"; }
    @Override String alvo() { return "com.stremio.one"; }
}
