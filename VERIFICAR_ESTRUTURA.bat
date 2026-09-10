@echo off
if not exist settings.gradle echo FALTA settings.gradle
if not exist build.gradle echo FALTA build.gradle
if not exist gradle.properties echo FALTA gradle.properties
if not exist app\build.gradle echo FALTA app\build.gradle
if not exist app\src\main\AndroidManifest.xml echo FALTA AndroidManifest.xml
if not exist app\src\main\java\com\lotomania\metrica20\MainActivity.java echo FALTA MainActivity.java
echo Conferencia terminada.
pause
