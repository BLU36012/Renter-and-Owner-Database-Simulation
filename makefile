JCC = javac
JFLAGS = -cp ojdbc11.jar -d .

JAR = jar
JARFLAGS = cfmv

all: Capacity.jar
Capacity.jar: Capacity.class Manifest.txt
	$(JAR) $(JARFLAGS) Capacity.jar Manifest.txt Capacity.class
Capacity.class: Capacity/Capacity.java
	$(JCC) $(JFLAGS) Capacity/Capacity.java

run: Capacity.jar
	java -jar Capacity.jar

clean:
	rm -f Capacity.class Capacity.jar
	