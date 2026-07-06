FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./build.ps1 2>/dev/null || true && \
    find src/main/java -name '*.java' > /tmp/sources && \
    mkdir -p build/classes && javac --release 21 -encoding UTF-8 -d build/classes @/tmp/sources && \
    printf 'Main-Class: dev.swissknife.Main\nImplementation-Version: 2.0.0\n' > build/MANIFEST.MF && \
    jar --create --file build/swissknife.jar --manifest build/MANIFEST.MF -C build/classes .

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 swissknife
WORKDIR /workspace
COPY --from=build /workspace/build/swissknife.jar /opt/swissknife/swissknife.jar
USER 10001
ENTRYPOINT ["java","-jar","/opt/swissknife/swissknife.jar"]
CMD ["help"]
