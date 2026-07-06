# Contribuindo

Use JDK 21 ou superior. No Windows, execute `test.cmd`; em Linux/macOS, execute
`./test.sh`. Toda mudança deve preservar compatibilidade da CLI, incluir teste
reproduzível e atualizar a documentação afetada.

Novas ferramentas devem ficar em pacote próprio, devolver records serializáveis,
não imprimir diretamente no core e nunca persistir credenciais. Operações
destrutivas precisam oferecer prévia, confirmação e rollback quando possível.
