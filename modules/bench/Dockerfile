FROM eclipse-temurin:21

WORKDIR /opt/xtdb

# Enable core dumps (unlimited size)
RUN ulimit -c unlimited || true

ENTRYPOINT [ \
    "java","-cp","xtdb-bench.jar", \
    "-Dclojure.main.report=stderr", \
    "-Darrow.memory.debug.allocator=true", \
    "-XX:ErrorFile=/var/crash-logs/hs_err_pid%p.log", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/var/crash-logs/heap_dump.hprof", \
    "-Xms3g", "-Xmx3g", \
    "--add-opens=java.base/java.nio=ALL-UNNAMED", \
    "--enable-native-access=ALL-UNNAMED", \
    "-Dio.netty.tryReflectionSetAccessible=true", \
    "clojure.main", "-m", "xtdb.bench"]

ADD build/libs/bench-standalone.jar xtdb-bench.jar
