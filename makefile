SRC_DIR  := $(firstword $(wildcard ryw229) LUUncommons)
SOURCES  := $(wildcard $(SRC_DIR)/*.java)
BUILD    := build
JAR      := ryw229.jar

JC       := javac
JFLAGS   := --release 11 -d $(BUILD)

all: $(JAR)

$(JAR): $(SOURCES) Manifest.txt
	rm -rf $(BUILD)
	mkdir -p $(BUILD)
	$(JC) $(JFLAGS) $(SOURCES)
	jar cfm $(JAR) Manifest.txt -C $(BUILD) .

run: $(JAR)
	java -jar $(JAR)

clean:
	rm -rf $(BUILD) $(JAR) *.class $(SRC_DIR)/*.class

.PHONY: all run clean