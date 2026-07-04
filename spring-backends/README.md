# Backends Spring Boot

Implementações Spring Boot completas das APIs de vulnerabilidades e ITAM, com
Spring Web, Bean Validation, Spring Data JPA, H2, Flyway e Actuator.

```text
mvn test
mvn -pl vulnerability-service spring-boot:run
mvn -pl itam-service spring-boot:run
```

Configure `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` e `PORT` para
produção. Os defaults usam H2 persistente em `./data`.
