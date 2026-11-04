package br.casa.tvkeys;

/** Green (scan 399): the app chosen in Cinema (Ajustes → Botões do controle); HBO Max without Cinema. */
public final class Verde extends Abrir {
    @Override String cor() { return "verde"; }
    @Override String alvo() { return "com.wbd.stream"; }
}
