package dev.swissknife.cli;

/**
 * Ponto de entrada abstrato para executar um comando da suíte.
 *
 * Existe para quebrar o ciclo de pacotes {@code dev.swissknife} ↔ {@code dev.swissknife.portal}
 * (violação de ADP): o portal precisa executar comandos, mas não pode importar {@code Main} — isso
 * invertia a direção de estabilidade, fazendo o núcleo depender de um subsistema periférico.
 * Agora ambos dependem desta abstração, que vive no pacote mais estável (cli).
 */
@FunctionalInterface
public interface CommandExecutor {
    /** Executa o comando já tokenizado e devolve o resultado serializável (ou null quando não há saída). */
    Object execute(String[] arguments) throws Exception;
}
