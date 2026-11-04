package br.casa.tvkeys;

/** Yellow (scan 400): the app chosen in Cinema (Ajustes → Botões do controle); Globoplay without Cinema. */
public final class Amarelo extends Abrir {
    @Override String cor() { return "amarelo"; }
    @Override String alvo() { return "com.globo.globotv"; }
}
