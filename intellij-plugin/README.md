# Plugin IntelliJ

O menu **Tools > SwissKnife** oferece geração de documentação, análise de
débito técnico, qualidade Java, segurança, arquitetura e auditoria Spring Boot.

O plugin procura o JAR nesta ordem:

1. variável `SWISSKNIFE_JAR`;
2. `build/swissknife.jar` no projeto;
3. `.swissknife/swissknife.jar` no projeto;
4. `~/.swissknife/swissknife.jar`.

Defina `SWISSKNIFE_IDE_TIMEOUT_MS` para ajustar o timeout das análises.

## Compilar e verificar

Use JDK 21 e Gradle 8.13:

```powershell
.\verify.cmd
```

O ZIP instalável é produzido em `build/distributions/`. A tarefa executa o
IntelliJ Plugin Verifier contra o IntelliJ IDEA Community 2025.1. Para testar
interativamente, execute a tarefa Gradle `runIde`.

O identificador publicado do plugin é `dev.swissknife.productivity`.
