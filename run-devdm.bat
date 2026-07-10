@echo off
set JAVA_OPTS=-Xms256m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m -Dspring.profiles.active=devdm
java -jar %JAVA_OPTS% ruoyi-admin.jar
pause