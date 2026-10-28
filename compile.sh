#!/bin/sh
# Compiles the whole project into the "out" folder.
mkdir -p out
javac -encoding UTF-8 -cp "lib/*" -d out $(find src -name "*.java") tests/AccountingTests.java && echo "Compiled OK. Now run ./run.sh"
