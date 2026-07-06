# Plugin IntelliJ

Abra esta pasta como projeto Gradle no IntelliJ IDEA e execute a tarefa
`runIde`. O menu **Tools > SwissKnife** oferece geração de documentação e
análise de débito técnico, qualidade Java, segurança, arquitetura e auditoria
Spring Boot.

O plugin procura o JAR nesta ordem:

1. variável `SWISSKNIFE_JAR`;
2. `build/swissknife.jar` no projeto;
3. `.swissknife/swissknife.jar` no projeto;
4. `~/.swissknife/swissknife.jar`.

Defina `SWISSKNIFE_IDE_TIMEOUT_MS` para ajustar o timeout das análises.
