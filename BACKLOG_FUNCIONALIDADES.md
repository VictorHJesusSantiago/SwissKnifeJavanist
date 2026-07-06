1. Plataforma e experiência da CLI
Arquivo global de configuração .swissknife.yml.
Configuração específica por projeto.
Perfis para desenvolvimento, homologação e produção.
Mesclagem de configuração por arquivo, variável de ambiente e argumento.
Comando init para configurar um projeto interativamente.
Comando doctor para diagnosticar Java, drivers, permissões e configuração.
Comando version.
Verificação opcional de novas versões.
Autocompletar para PowerShell, Bash, Zsh e Fish.
Aliases configuráveis para comandos.
Flags padronizadas --help, --version, --verbose, --quiet e --debug.
Flag --dry-run para operações que alteram arquivos ou bancos.
Entrada por stdin.
Saída por stdout, sem obrigar arquivo.
Formatos de saída JSON, texto, Markdown, CSV, HTML, XML e YAML.
Formato SARIF para integração com IDEs e plataformas de código.
Formato JUnit XML para CI.
Códigos de saída diferenciados para erro, alerta e reprovação de política.
Limites configuráveis que reprovem o pipeline.
Filtros globais de inclusão e exclusão de arquivos.
Respeito automático a .gitignore.
Arquivo próprio .swissknifeignore.
Processamento incremental de arquivos alterados.
Cache local de análises.
Invalidação automática de cache.
Modo watch.
Execução paralela das análises independentes.
Comando que execute várias ferramentas em um único pipeline.
Relatório consolidado de todas as ferramentas.
Comparação entre relatório atual e baseline.
Supressões justificadas e com data de expiração.
Presets de políticas para projeto pequeno, corporativo e regulado.
Suporte a caminhos relativos consistentes.
Mensagens de erro com sugestões de correção.
Saída colorida e desativação por NO_COLOR.
Indicador de progresso para operações longas.
Cancelamento seguro com Ctrl+C.
Logs estruturados.
Medição do tempo de cada etapa.
Telemetria local opt-in sobre uso dos comandos.
API Java pública para embutir as ferramentas.
SPI para extensões de terceiros.
Sistema de plugins locais.
Plugin Maven.
Plugin Gradle.
GitHub Action reutilizável.
Imagem Docker oficial.
Distribuição via JAR, jlink e executável nativo.
Instaladores para Windows, Linux e macOS.
Assinatura e checksum dos artefatos publicados.
2. Geração de documentação Java
O gerador atual reconhece basicamente tipos e métodos públicos por expressões regulares.
Parser baseado na árvore sintática do Java em vez de regex.
Documentação de construtores.
Documentação de campos.
Documentação de constantes.
Documentação de componentes de records.
Documentação de tipos aninhados.
Documentação de métodos protegidos.
Inclusão opcional de membros privados.
Documentação de annotations.
Documentação de módulos Java.
Páginas por pacote.
Índice geral de pacotes e tipos.
Hierarquia de herança.
Relações de implementação de interfaces.
Resolução de tipos genéricos.
Links entre tipos referenciados.
Assinaturas completas com annotations e exceções.
Interpretação de @param, @return, @throws, @since e @deprecated.
Preservação de blocos de código do Javadoc.
Validação de parâmetros sem documentação.
Detecção de Javadocs ausentes ou desatualizados.
Métrica de cobertura documental.
Política mínima de cobertura.
Extração de exemplos executáveis.
Inclusão de referências ao arquivo e à linha do código.
Links para GitHub, GitLab ou Bitbucket.
Diagrama UML de classes.
Diagrama de pacotes.
Diagrama de sequência inferido para fluxos simples.
Grafo de chamadas.
Catálogo de endpoints Spring MVC.
Geração de OpenAPI a partir de controllers.
Catálogo de entidades JPA.
Diagrama entidade-relacionamento.
Catálogo de propriedades application.yml.
Catálogo de eventos Kafka/RabbitMQ.
Catálogo de tarefas agendadas.
Catálogo de migrations Flyway e Liquibase.
Catálogo automático de ADRs.
Detecção de links quebrados.
Geração incremental.
Templates personalizáveis.
Temas claro e escuro.
Saída HTML pesquisável.
Saída AsciiDoc.
Site estático completo.
Exportação para MkDocs, Docusaurus e Antora.
Comparação de documentação entre versões.
Changelog de API pública.
Detecção de breaking changes na API Java.
3. Mapa de dependências e arquitetura
Hoje o grafo depende de arquivos manuais service.properties.
Descoberta de módulos em projetos Maven.
Descoberta de módulos em projetos Gradle.
Leitura de dependências de pom.xml.
Leitura de dependências de build.gradle e build.gradle.kts.
Descoberta de serviços em Docker Compose.
Descoberta em manifests Kubernetes.
Descoberta em Helm charts.
Descoberta de clientes HTTP Spring.
Descoberta de OpenFeign clients.
Descoberta de chamadas via RestClient, RestTemplate e WebClient.
Descoberta de produtores e consumidores Kafka.
Descoberta de RabbitMQ.
Descoberta de bancos compartilhados.
Descoberta de caches Redis.
Descoberta de dependências por configuração.
Importação de OpenAPI.
Importação de AsyncAPI.
Metadados de protocolo, URL, porta e ambiente em cada ligação.
Diferenciação entre dependência síncrona e assíncrona.
Diferenciação entre dependência obrigatória e opcional.
Detecção de dependências desconhecidas.
Detecção de serviços referenciados, mas não cadastrados.
Detecção de serviços órfãos.
Detecção de dependências circulares.
Detecção de ciclos indiretos.
Cálculo de caminhos críticos.
Cálculo de centralidade e raio de impacto.
Análise “quem depende deste serviço?”.
Análise de impacto de uma mudança.
Comparação do grafo entre branches ou versões.
Comparação entre ambientes.
Detecção de divergência arquitetural.
Regras como “domínio A não pode depender de domínio B”.
Limites máximos de acoplamento.
Validação de arquitetura em camadas.
Validação de arquitetura hexagonal.
Agrupamento por domínio, squad ou bounded context.
Cadastro de responsáveis e contatos.
Integração com CODEOWNERS.
Estado de saúde dos serviços no diagrama.
Exibição de versão implantada.
Exibição de vulnerabilidades por componente.
Exibição dos ativos associados.
Exportação para Mermaid, PlantUML, Graphviz e JSON.
Página HTML interativa com busca e filtros.
Zoom e navegação entre dependências.
Geração de C4 Context, Container e Component.
Histórico visual da evolução arquitetural.
4. Análise de SQL e queries lentas
O analisador atual usa heurísticas simples por regex.
Leitura de SQL de arquivos.
Análise de várias queries em lote.
Extração de queries de logs.
Extração de logs Hibernate.
Extração de logs JDBC.
Extração de traces APM.
Parser SQL real.
Suporte específico a PostgreSQL.
Suporte a MySQL/MariaDB.
Suporte a SQL Server.
Suporte a Oracle.
Suporte a H2.
Reconhecimento de aliases.
Reconhecimento de subqueries.
Reconhecimento de CTEs.
Reconhecimento de UNION.
Análise de GROUP BY e HAVING.
Análise de funções de janela.
Análise de joins múltiplos.
Sugestão de ordem das colunas em índices compostos.
Sugestão de índices parciais.
Sugestão de índices funcionais.
Sugestão de índices de cobertura.
Detecção de índices redundantes.
Detecção de índices duplicados.
Detecção de índices nunca usados.
Conexão opcional ao banco para coletar metadados.
Execução segura de EXPLAIN.
Leitura de EXPLAIN ANALYZE fornecido pelo usuário.
Visualização do plano de execução.
Detecção de sequential scan.
Detecção de estimativas incorretas.
Detecção de joins caros.
Detecção de sorts em disco.
Estimativa de seletividade.
Uso de estatísticas reais das tabelas.
Detecção de paginação com OFFSET elevado.
Sugestão de paginação por cursor.
Detecção de conversões implícitas de tipo.
Detecção de OR que prejudique índices.
Detecção de NOT IN problemático.
Detecção de queries N+1 a partir de logs.
Detecção de atualizações ou exclusões sem filtro.
Análise de contenção e locks.
Sugestões de reescrita da query.
Comparação de desempenho antes e depois.
Histórico de queries analisadas.
Baseline de tempo e regressão.
Ranking das queries mais problemáticas.
Relatório HTML com riscos e recomendações.
Geração de migrations para os índices sugeridos.
Política que impeça SQL destrutivo no CI.
5. Comparação e governança de schemas
Hoje são comparadas apenas tabelas, colunas, tipos e nulabilidade básicos.
Comparação de chaves primárias.
Comparação de chaves estrangeiras.
Comparação de índices.
Comparação de constraints UNIQUE.
Comparação de constraints CHECK.
Comparação de valores default.
Comparação de identidade e auto incremento.
Comparação de sequences.
Comparação de views.
Comparação de materialized views.
Comparação de triggers.
Comparação de procedures e functions.
Comparação de enums do banco.
Comparação de comentários de schema.
Comparação de collations.
Comparação de schemas/namespaces.
Reconhecimento de renomeação de tabela.
Reconhecimento de renomeação de coluna.
Matriz de compatibilidade entre tipos.
Identificação de redução perigosa de tamanho.
Estimativa de possível perda de dados.
Ordem correta das mudanças por dependência.
Script completo de atualização.
Script de rollback.
Scripts idempotentes.
Geração de migration Flyway.
Geração de changelog Liquibase.
Numeração automática de migration.
Introspecção de schema diretamente via JDBC.
Comparação arquivo versus banco.
Comparação banco versus banco.
Comparação entre ambientes.
Detecção contínua de schema drift.
Comparação de dados de referência.
Sincronização opcional de seeds.
Pré-condições antes de executar alterações.
Backup obrigatório antes de alterações destrutivas.
Aplicação assistida das mudanças.
Confirmação explícita para cada mudança destrutiva.
Execução transacional quando suportada.
Validação posterior à aplicação.
Lista de objetos ignorados.
Mapeamento de tipos entre SGBDs.
Relatório HTML do diff.
Grafo das dependências do schema.
6. Anonimização e privacidade
Hoje a entrada é CSV e existem seis transformações simples.
Processamento streaming de arquivos grandes.
CSV com delimitador configurável.
Suporte a TSV.
Suporte a múltiplas codificações.
Suporte correto a campos CSV multilinha.
Entrada e saída JSON.
Entrada e saída JSON Lines.
Anonimização de dumps SQL.
Anonimização direta via JDBC.
Anonimização de várias tabelas.
Preservação de relacionamentos entre tabelas.
Pseudonimização determinística.
Hash com salt configurável.
HMAC com chave externa.
Tokenização reversível.
Cofre separado para destokenização.
Geração de nomes realistas.
Geração de endereços.
Geração de empresas.
Tratamento de CPF e CNPJ.
Tratamento de CEP.
Tratamento de telefone por país.
Tratamento de cartões com algoritmo de Luhn.
Preservação de domínio de e-mail.
Preservação parcial de valores.
Perturbação de números por intervalo.
Deslocamento consistente de datas.
Generalização de idade e localização.
Embaralhamento de valores entre registros.
Substituição por dicionários personalizados.
Regras por regex.
Regras condicionais entre colunas.
Tratamento de objetos JSON aninhados.
Garantia de unicidade após anonimização.
Preservação de formato.
Preservação de distribuição estatística.
Mapeamento consistente entre múltiplos arquivos.
Detecção automática de possíveis dados pessoais.
Classificação LGPD de colunas.
Prévia das transformações.
Amostragem antes da execução.
Relatório do que foi transformado.
Validação de ausência de dados originais.
Regras de exclusão de colunas.
Políticas versionadas.
Validação da política antes de processar.
Paralelização segura.
Retomada após interrupção.
Limpeza segura de arquivos temporários.
7. Testes de contrato HTTP
Hoje há uma única requisição, comparação de status e busca de substrings.
Suítes com vários contratos.
Headers de requisição configuráveis.
Query parameters.
Path parameters.
Cookies.
Autenticação Bearer.
Basic Auth.
API keys.
OAuth 2 client credentials.
Certificados de cliente.
Corpo JSON estruturado.
Upload multipart.
Form URL encoded.
Validação de headers da resposta.
Validação de cookies da resposta.
Validação por JSONPath.
Validação por XPath.
Validação por regex.
Validação de tipos.
Validação de valores mínimos e máximos.
Validação de arrays sem depender da ordem.
JSON Schema.
XML Schema.
Limite de tempo de resposta.
Retries configuráveis.
Variáveis por ambiente.
Segredos via variável de ambiente.
Extração de valores de uma resposta.
Encadeamento entre requisições.
Setup e teardown.
Massa de dados.
Execução parametrizada.
Execução paralela.
Testes negativos.
Geração de casos de borda.
Fuzz testing controlado.
Importação de OpenAPI.
Geração de contratos a partir de tráfego.
Snapshots de respostas.
Detecção de breaking changes.
Contratos orientados ao consumidor.
Publicação e versionamento de contratos.
Mock server.
Stubs gerados automaticamente.
Modo offline contra respostas gravadas.
Testes GraphQL.
Testes WebSocket.
Testes de eventos AsyncAPI.
Relatório JUnit XML.
Relatório HTML.
Redação de dados sensíveis nos relatórios.
8. Geração e execução de testes de carga
Hoje é gerado apenas um cenário Gatling linear simples.
Headers por endpoint.
Bodies JSON.
Query e path parameters.
Cookies e sessões.
Autenticação.
Feeders CSV, JSON e JDBC.
Variáveis e correlação entre respostas.
Checks JSONPath, XPath e regex.
Pausas e think time.
Grupos e transações.
Repetições e loops.
Condições e ramificações.
Cenários múltiplos.
Usuários constantes.
Rampas em estágios.
Picos de carga.
Teste de estresse.
Teste de endurance/soak.
Teste de capacidade.
Modelo de chegada aberta e fechada.
Limite de requests por segundo.
Assertions de latência.
Assertions de throughput.
Assertions de taxa de erro.
SLAs globais e por endpoint.
Aquecimento antes da medição.
Setup e cleanup de dados.
Importação de OpenAPI.
Importação de HAR.
Conversão de contrato HTTP em cenário de carga.
Execução do Gatling pela própria CLI.
Download/configuração assistida do Gatling.
Coleta automática dos resultados.
Resumo estatístico na CLI.
Relatório HTML consolidado.
Comparação com baseline.
Detecção automática de regressão.
Histórico de execuções.
Gráficos de percentis.
Integração com Prometheus/Grafana.
Teste distribuído opcional.
Redação de credenciais nos scripts gerados.
9. Rastreamento de dívida técnica
Hoje são encontrados apenas TODO, FIXME, HACK e XXX em alguns formatos.
Marcadores personalizados.
Mais extensões de arquivos.
Exclusão de diretórios gerados.
Leitura de autor em TODO(usuario).
Leitura de ticket associado.
Leitura de prazo.
Leitura de severidade explícita.
Validação do formato dos marcadores.
Idade do débito pelo histórico Git.
Autor original via git blame.
Última alteração do item.
Detecção de TODO sem descrição.
Detecção de TODO sem responsável.
Detecção de referência a ticket inexistente.
Agrupamento por projeto, módulo e pacote.
Agrupamento por responsável.
Priorização por idade, severidade e criticidade.
Tendência de criação e resolução.
Comparação com baseline.
Política de “não aumentar dívida”.
Orçamento máximo de dívida.
Supressões justificadas.
Datas de expiração para supressões.
Exportação para SARIF.
Exportação para CSV e HTML.
Criação de issues no GitHub, GitLab ou Jira.
Sincronização bidirecional com tickets.
Dashboard histórico.
Notificação de dívida vencida.
Links clicáveis para arquivo e linha.
Detecção de duplicatas semelhantes.
Sugestão assistida de solução.
Estimativa de esforço.
Registro de dívida arquitetural e não apenas comentários.
10. Migração de bancos
Hoje é copiada uma tabela inteira com colunas de mesmo nome.
Migração de múltiplas tabelas.
Migração de um schema completo.
Descoberta automática das tabelas.
Ordem por dependências de chaves estrangeiras.
Seleção de colunas.
Renomeação de colunas.
Renomeação de tabelas.
Mapeamento de tipos entre bancos.
Transformações configuráveis.
Filtro WHERE.
Migração por intervalo de chave.
Migração incremental.
Checkpoints persistentes.
Retomada depois de falha.
Estratégias insert, update e upsert.
Política para conflitos.
Truncamento opcional do destino.
Criação automática da estrutura de destino.
Cópia de índices e constraints.
Cópia de sequences.
Ajuste das sequences após a carga.
Migração preservando chaves geradas.
Streaming de BLOB e CLOB.
Tratamento de data, hora e timezone.
Configuração de fetch size.
Paralelização por partições.
Controle de memória.
Progresso e estimativa de tempo.
Métricas de throughput.
Dry run.
Validação de conectividade.
Validação de permissões.
Contagem antes e depois.
Checksums por lote.
Amostragem comparativa.
Relatório de divergências.
Arquivo de registros rejeitados.
Política de retry.
Cancelamento e rollback seguros.
Desativação e reativação controlada de constraints.
Migração com zero ou baixo downtime.
Migração para CSV/JSON e a partir deles.
Perfis reutilizáveis sem senhas.
Propriedades avançadas do driver JDBC.
TLS e certificados JDBC.
Auditoria completa da execução.
11. Gestão de vulnerabilidades
Hoje existem CRUD, severidade, status e um dashboard agregado.
Campos CVE, CWE e CVSS.
Vetor CVSS.
Fonte da descoberta.
Dependência e versão afetada.
Versão corrigida.
Repositório, branch e commit.
URL de referência.
Evidências e anexos.
Responsável.
Squad e projeto.
Datas de descoberta, vencimento e resolução.
SLA por severidade.
Status vencido automaticamente.
Comentários.
Histórico de alterações.
Workflow configurável.
Reabertura de vulnerabilidade.
Duplicação e mesclagem.
Busca textual.
Filtros.
Ordenação.
Paginação.
Operações em lote.
Tags e campos personalizados.
Importação de SARIF.
Importação de CycloneDX e SPDX.
Importação de scanners SAST, DAST e SCA.
Importação de Dependabot, Trivy, OWASP Dependency-Check e Semgrep.
Sincronização com OSV/NVD.
Enriquecimento com EPSS.
Identificação de vulnerabilidades no catálogo KEV.
Priorização por explorabilidade e exposição.
Distinção entre falso positivo e risco aceito.
Aprovação formal de aceitação de risco.
Expiração automática da aceitação.
Plano e recomendação de correção.
Associação com pull request de correção.
Verificação automática de remediação.
Associação com ativos do ITAM.
Associação com serviços do mapa arquitetural.
Notificações por e-mail, Slack, Teams e webhook.
Integração com Jira, GitHub e GitLab.
Relatórios executivo, técnico e de conformidade.
Tendências de abertura, resolução e backlog.
MTTR por severidade e equipe.
Aging do backlog.
Matriz de risco.
Exportação CSV, JSON, PDF e SARIF.
Deduplicação entre scanners.
Agendamento de importações e scans.
Auditoria imutável.
Controle de acesso por projeto.
Exclusão lógica e restauração.
12. Gestão de ativos de TI
Hoje existem CRUD, tipos, estados, responsável textual e valor de compra.
Categorias e subcategorias personalizadas.
Fabricante e modelo.
Número de série único.
Hostname, IP e MAC.
Sistema operacional e versão.
Especificações de hardware.
Localização física.
Departamento e centro de custo.
Cadastro de usuários responsáveis.
Cadastro de fornecedores.
Cadastro de contratos.
Documentos e anexos.
Fotos do ativo.
Tags livres.
Campos personalizados.
Histórico completo de movimentações.
Checkout e devolução.
Termo de responsabilidade.
Assinatura/aceite do usuário.
Reserva de ativos.
Estoque mínimo.
Alertas de estoque.
Ciclo de vida completo.
Solicitação de compra.
Pedido e recebimento.
Garantia e alerta de vencimento.
Manutenção preventiva.
Ordens de serviço.
Histórico de reparos e custos.
Depreciação contábil.
Valor residual.
Licenças de software.
Instalações de software por ativo.
Controle de quantidade de licenças.
Alertas de licenças excedidas ou ociosas.
Associação entre ativo físico e virtual.
Relações entre servidor, VM, serviço e banco.
Descoberta de rede por ping/SNMP/SSH.
Importação de inventário de agentes.
Reconciliação entre descoberto e cadastrado.
Geração e leitura de QR Code.
Geração e leitura de código de barras.
Impressão de etiquetas.
Inventário físico por campanha.
Registro de divergências.
Ativos perdidos, roubados ou danificados.
Processo de descarte seguro.
Certificado de descarte.
Busca, filtros e paginação.
Atualização em lote.
Importação e exportação CSV/Excel.
Relatórios por tipo, status, local e responsável.
Custos por departamento.
Dashboard de utilização.
Associação com vulnerabilidades.
Associação com incidentes.
Auditoria de todas as mudanças.
13. Interface web unificada
O projeto não possui frontend.
Portal único para todas as ferramentas.
Dashboard inicial de governança.
Navegação entre vulnerabilidades, ativos, dívida e arquitetura.
Execução dos comandos da CLI por formulário.
Visualização de progresso de jobs.
Histórico de execuções.
Download dos artefatos gerados.
Visualizador de Markdown.
Visualizador interativo de Mermaid.
Visualizador de schemas.
Editor de políticas de anonimização.
Editor de contratos HTTP.
Editor de cenários de carga.
Comparador visual de relatórios.
Busca global.
Filtros salvos.
Dashboards configuráveis.
Temas claro e escuro.
Layout responsivo.
Acessibilidade por teclado e leitores de tela.
Internacionalização.
Centro de notificações.
Gestão de usuários, papéis e tokens.
Página administrativa de configuração.
Ajuda contextual e exemplos.
Atualizações em tempo real por SSE/WebSocket.
Interface instalável como PWA.
14. Plugin IntelliJ
Atualmente o plugin apenas chama docs e debt.
Ações para todos os comandos da CLI.
Página de configurações do plugin.
Descoberta automática do JAR.
Download da versão compatível.
Seleção do executável Java.
Tool window própria.
Exibição estruturada dos resultados.
Links clicáveis para arquivo e linha.
Gutter icons para dívida técnica.
Inspections nativas.
Quick fixes.
Intenção para criar ou atualizar Javadoc.
Visualização do grafo arquitetural.
Visualização do schema diff.
Editor visual de políticas de anonimização.
Run configuration para contratos.
Run configuration para testes de carga.
Integração com Services para os dois backends.
CRUD de vulnerabilidades na IDE.
Consulta de ativos na IDE.
Análise automática ao salvar.
Análise apenas dos arquivos alterados.
Análise antes de commit.
Integração com Problems tool window.
Integração com notificações da IDE.
Status da governança na status bar.
Comparação com baseline.
Configuração de supressões pelo editor.
Logs em console dedicado.
Cancelamento de processos.
Suporte a projetos sem a raiz do SwissKnife.
Compatibilidade com mais versões do IntelliJ.
Testes automatizados do plugin.
Publicação no JetBrains Marketplace.
15. APIs e backends
Paridade funcional entre servidor leve e Spring.
Contratos OpenAPI para todas as APIs.
Swagger UI.
Versionamento formal das APIs.
Paginação padrão.
Busca, filtros e ordenação.
Respostas RFC 9457 Problem Details.
Validação consistente.
Tratamento global de exceções.
IDs de correlação.
ETags e requisições condicionais.
Controle otimista de concorrência.
Chaves de idempotência.
Limite de tamanho de payload.
Compressão HTTP.
CORS configurável.
Rate limiting.
Quotas por cliente.
Endpoints de readiness e liveness separados.
Endpoint de versão/build.
Jobs assíncronos para operações longas.
Consulta do estado dos jobs.
Cancelamento de jobs.
Webhooks.
Server-Sent Events.
Importação e exportação em lote.
Soft delete.
Política de retenção.
PostgreSQL e MySQL além de H2.
Migração do armazenamento JSON para SQL.
Backup online consistente.
Restauração validada.
Pool e tuning de conexões.
Cache com invalidação.
Arquivamento de registros antigos.
Testes de compatibilidade entre versões da API.
16. Segurança e controle de acesso
Autenticação também nos backends Spring.
Usuários persistidos.
RBAC.
Permissões por projeto e recurso.
OAuth 2/OIDC.
Login via GitHub, GitLab, Google ou provedor corporativo.
API keys múltiplas.
Escopos por token.
Rotação de tokens.
Expiração de tokens.
Revogação.
Hash seguro de credenciais.
MFA para administração.
TLS nativo opcional.
Suporte a reverse proxy confiável.
Headers HTTP de segurança.
Proteção CSRF quando aplicável.
Rate limiting por identidade.
Bloqueio após tentativas inválidas.
Registro de login e falhas.
Audit log imutável.
Redação de segredos nos logs.
Criptografia de campos sensíveis.
Integração com secret managers.
Validação contra path traversal.
Limites de memória e payload.
Scanner de segredos em código e configuração.
Inventário de certificados.
Alertas de expiração de certificados.
Políticas de retenção LGPD.
Exportação e eliminação de dados pessoais.
Assinatura dos relatórios e artefatos.
17. Dependências Java, SBOM e licenças
Este é um módulo novo, mas central à ideia do produto.
Inventário de dependências Maven.
Inventário de dependências Gradle.
Árvore de dependências transitivas.
Detecção de conflitos de versão.
Detecção de dependências duplicadas.
Detecção de dependências não utilizadas.
Detecção de dependências declaradas indiretamente.
Verificação de versões desatualizadas.
Sugestões de atualização segura.
Análise de impacto da atualização.
Identificação de breaking changes conhecidas.
Detecção de bibliotecas abandonadas.
Vulnerabilidades via OSV ou banco importado.
Geração de SBOM CycloneDX.
Geração de SBOM SPDX.
Comparação de SBOMs.
Assinatura e atestação do SBOM.
Catálogo de licenças.
Políticas de licenças permitidas e proibidas.
Detecção de licença ausente.
Relatório de atribuições.
Detecção de dependência comprometida por política.
Governança de repositórios Maven autorizados.
Verificação de checksums.
Detecção de dependency confusion.
Lockfile ou catálogo de versões recomendado.
BOM corporativo sugerido.
Integração com o backend de vulnerabilidades.
18. Qualidade e arquitetura do código Java
Métricas de linhas, classes, métodos e complexidade.
Complexidade ciclomática.
Complexidade cognitiva.
Métodos e classes excessivamente grandes.
Número excessivo de parâmetros.
Profundidade de herança.
Acoplamento entre pacotes.
Coesão de classes.
Dependências cíclicas entre pacotes.
Código duplicado.
Código morto.
Imports e membros não utilizados.
APIs públicas sem uso.
Exceções engolidas.
Catch genérico.
Uso incorreto de Optional.
Recursos não fechados.
Possíveis NPEs.
Concorrência insegura.
Bloqueios em virtual threads.
Uso problemático de APIs obsoletas.
Regras específicas para records, sealed classes e pattern matching.
Validação de arquitetura em camadas.
Validação de portas e adaptadores.
Regras customizadas.
Baseline para código legado.
Relatório SARIF.
Quality gate para CI.
Tendência histórica das métricas.
Sugestões automáticas de refatoração.
Aplicação opcional de correções mecânicas seguras.
19. Governança de Spring Boot e configuração
Inventário de aplicações Spring.
Catálogo de beans relevantes.
Catálogo de controllers.
Catálogo de repositories.
Catálogo de scheduled tasks.
Catálogo de listeners.
Detecção de propriedades desconhecidas.
Detecção de propriedades duplicadas.
Comparação de application.yml entre ambientes.
Detecção de segredos em configuração.
Detecção de Actuator exposto indevidamente.
Verificação de configuração de CORS.
Verificação de headers de segurança.
Verificação de Open Session in View.
Verificação de pool JDBC.
Verificação de timeouts HTTP.
Verificação de retries e circuit breakers.
Verificação de logs com dados sensíveis.
Detecção de endpoints sem autenticação.
Detecção de migrations incompatíveis.
Validação de profiles.
Relatório de auto-configurações.
Detecção de dependências Spring incompatíveis.
Sugestões de upgrade do Spring Boot.
Geração de metadata de configuração.
20. Modernização e upgrade de projetos Java
Análise de compatibilidade com nova versão do JDK.
Inventário de APIs removidas ou depreciadas.
Migração assistida para Java 21 ou versões posteriores.
Conversão de classes de dados para records.
Sugestão de sealed classes.
Modernização de switch.
Modernização de instanceof.
Uso de text blocks.
Uso seguro de virtual threads.
Migração de javax.* para jakarta.*.
Migração entre versões do Spring Boot.
Migração entre versões de JUnit.
Migração de Maven ou Gradle.
Atualização automática de plugins de build.
Receita de transformação estilo OpenRewrite.
Prévia do patch.
Aplicação seletiva.
Rollback das alterações.
Compilação e testes após cada transformação.
Relatório de mudanças manuais restantes.
Estimativa de esforço da modernização.
21. Diagnóstico de JVM e operação
Análise de thread dumps.
Detecção de deadlocks.
Detecção de threads bloqueadas.
Agrupamento de stack traces.
Comparação de thread dumps.
Análise de GC logs.
Pausas e throughput do GC.
Recomendação de heap.
Detecção de pressão de memória.
Análise básica de histogramas de heap.
Análise de Java Flight Recorder.
Resumo de hotspots.
Detecção de contenção.
Análise de startup.
Análise de logs Spring Boot.
Agrupamento de exceções.
Detecção de padrões repetitivos.
Correlação por trace/correlation ID.
Redação de dados sensíveis em logs.
Monitor local de processos Java.
Inventário de JVMs em execução.
Portas e endpoints expostos.
Coleta de informações para suporte.
Bundle de diagnóstico.
Comparação antes/depois de uma implantação.
Alertas baseados em limites locais.
22. Testes e produtividade de desenvolvimento
Geração de skeletons de testes JUnit.
Geração de testes para controllers.
Geração de testes para services e repositories.
Geração de fixtures.
Builders de objetos de teste.
Massa de dados SQL.
Detecção de testes flaky.
Repetição controlada para confirmar flakiness.
Quarentena de testes.
Análise de duração da suíte.
Ranking de testes lentos.
Paralelização recomendada.
Test impact analysis.
Execução apenas dos testes afetados.
Identificação de código sem cobertura.
Integração com JaCoCo.
Quality gate de cobertura.
Mutation testing.
Detecção de testes sem assertions.
Detecção de testes dependentes de ordem.
Detecção de portas e datas fixas.
Gerenciamento de containers de teste.
Geração de mocks de serviços externos.
Snapshot testing.
Relatório consolidado de unitários, integração, contrato e carga.
23. CI/CD e políticas de governança
Arquivos prontos para GitHub Actions.
Templates para GitLab CI.
Templates para Jenkins.
Templates para Azure DevOps.
Modo “somente arquivos alterados”.
Comentário automático em pull requests.
Annotations diretamente nas linhas alteradas.
Resumo executivo do pipeline.
Quality gates configuráveis.
Políticas por branch.
Políticas por módulo.
Bloqueio de regressões, sem penalizar legado existente.
Upload de SARIF.
Publicação de relatórios HTML.
Armazenamento e comparação de baselines.
Assinatura de artefatos.
Proveniência de build.
Geração de SBOM no pipeline.
Verificação de migrations.
Verificação de contratos.
Smoke tests pós-deploy.
Comparação de configuração entre deploys.
Rollback sugerido quando gates falharem.
Notificações para Slack, Teams e e-mail.
Dashboard histórico de qualidade.
Métricas DORA complementares.
Relatório de prontidão para release.
24. Integrações
GitHub Issues e Pull Requests.
GitLab Issues e Merge Requests.
Bitbucket.
Jira.
Azure Boards.
Slack.
Microsoft Teams.
E-mail SMTP.
Webhooks genéricos.
Prometheus.
Grafana.
OpenTelemetry.
Elasticsearch/OpenSearch.
SonarQube.
Sentry.
Dependabot.
Renovate.
Trivy.
Semgrep.
OWASP Dependency-Check.
DefectDojo.
Backstage.
ServiceNow.
LDAP/Active Directory.
Importação e exportação genérica via CSV/JSON.
SDK Java para integrações personalizadas.
25. Observabilidade e operação do próprio SwissKnife
Métricas Micrometer nos backends.
Métricas Prometheus.
Tracing OpenTelemetry.
Logs JSON.
IDs de correlação.
Métricas de latência por endpoint.
Métricas de erros.
Métricas de banco e pool.
Dashboards Grafana prontos.
Readiness verificando dependências reais.
Graceful shutdown.
Backpressure para jobs.
Limites de concorrência.
Filas para tarefas longas.
Scheduler persistente.
Retry com backoff.
Dead-letter para jobs falhos.
Histórico e retenção de jobs.
Backup agendado.
Validação automática de backup.
Restore test automatizado.
Limpeza e compactação do JSON Lines.
Detecção e recuperação de arquivo corrompido.
Rotação de logs.
Rotação de audit logs.
Healthcheck dos serviços externos configurados.
26. Recursos opcionais apoiados por IA
Mantendo IA como auxílio, não como requisito para usar o produto:
Explicação de findings em linguagem simples.
Resumo executivo de relatórios.
Priorização contextual de vulnerabilidades.
Sugestões de correção de dívida técnica.
Sugestões de refatoração.
Explicação de planos SQL.
Sugestão de reescrita de queries.
Inferência de relacionamentos entre serviços.
Geração inicial de contratos HTTP.
Geração de cenários de carga.
Geração de política de anonimização revisável.
Classificação de possíveis dados pessoais.
Geração de documentação a partir do código.
Geração de ADR a partir de mudanças.
Resumo de breaking changes.
Geração de plano de migração de JDK/Spring.
Agrupamento semântico de erros.
Consulta aos relatórios em linguagem natural.
Modo offline com modelo local.
Suporte a múltiplos provedores.
Consentimento explícito antes de enviar código.
Redação automática de segredos antes do envio.
Limites de custo e tokens.
Cache de respostas.
Registro de modelo, prompt e origem da sugestão.
Exigência de revisão humana antes de aplicar alterações.
27. Qualidade interna e confiabilidade do projeto
Testes para contract-test, hoje sem cobertura direta.
Testes para debt, hoje sem cobertura direta.
Testes para migrate, hoje sem cobertura direta.
Testes completos de CRUD dos servidores leves.
Testes de autenticação por token.
Testes de concorrência do JsonStore.
Testes de corrupção e recuperação do armazenamento.
Testes de arquivos grandes.
Testes de propriedades para JSON e CSV.
Testes com Unicode e diferentes encodings.
Testes por dialeto SQL.
Testes de compatibilidade com Windows, Linux e macOS.
Testes end-to-end da CLI.
Testes end-to-end do plugin.
Testes com bancos reais via Testcontainers.
Testes de segurança das APIs.
Testes de carga dos próprios backends.
Cobertura JaCoCo.
Mutation testing.
Análise estática no próprio projeto.
Benchmarks JMH.
Build Maven ou Gradle padronizado para o core.
Dependabot/Renovate.
Releases semânticos.
Changelog automático.
Assinatura de releases.
Matriz oficial de compatibilidade.
Exemplos funcionais para todos os comandos.
Correção dos exemplos de anonimização ausentes no README.
Documentação completa das APIs Spring.
Guia de contribuição.
Roadmap público.
Política de segurança e divulgação responsável.