# language: es
Característica: Ingesta de telemetría sin pérdidas ni duplicados

  Escenario: Una lectura reenviada no se duplica
    Dado una lectura del dispositivo "EMSafe-DOS-01" con secuencia 120 ya almacenada
    Cuando la misma lectura llega otra vez por MQTT
    Entonces el dispositivo tiene una sola lectura con secuencia 120

  Escenario: Una lectura saturada se guarda como valor mínimo
    Cuando llega una lectura de 800 µT con calidad "SATURATED"
    Entonces la lectura se almacena con calidad "SATURATED" y un valor de 800 µT

  Escenario: Las lecturas diferidas conservan su hora de captura
    Cuando llegan 30 lecturas con origen "DEFERRED"
    Entonces se almacenan las 30 lecturas con la hora de captura del dosímetro
