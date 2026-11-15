# language: es
Característica: Umbral de alarma del dosímetro
  Como responsable de SSO
  Quiero definir el umbral de alarma del dosímetro
  Para que el operario reciba la alerta local en planta

  Antecedentes:
    Dado el dosímetro "EMSafe-DOS-01" de mi organización con la configuración en la versión 2

  Escenario: El responsable de SSO cambia el umbral
    Cuando defino un umbral de 270 µT
    Entonces la configuración del dosímetro pasa a la versión 3 con un umbral de 270 µT
    Y se registra una entrada de auditoría con el valor anterior y el nuevo
    Y se publica la configuración retenida en el tópico "iot/EMSafe-DOS-01/config"

  Escenario: Un umbral inválido no se guarda
    Cuando defino un umbral de -5 µT
    Entonces la solicitud es rechazada
    Y la configuración del dosímetro sigue en la versión 2
