package dev.swissknife.cli;

/**
 * Contrato de extensão para plugins de terceiros descobertos via {@link java.util.ServiceLoader}.
 * Para publicar um plugin, implemente esta interface e registre-a em
 * {@code META-INF/services/dev.swissknife.cli.SwissKnifePlugin} dentro de um JAR
 * colocado em {@code .swissknife/plugins/} (ou no classpath da CLI).
 */
public interface SwissKnifePlugin {
    /** Nome do comando que este plugin passa a atender (deve ser único). */
    String command();

    /** Descrição curta exibida em `swissknife --help` e na autocompletar. */
    String description();

    /** Executa o plugin; os argumentos começam em args[0] == command(). Retorna o objeto a ser formatado. */
    Object execute(String[] args, CliConfig config) throws Exception;
}
