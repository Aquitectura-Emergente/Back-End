# language: es
Característica: Orden al enchufe inteligente
  Como usuario del hogar
  Quiero apagar el aparato cuando el nivel es peligroso
  Para reducir la exposición en mi vivienda

  Escenario: La orden queda pendiente hasta que el Edge la confirma
    Dado el enchufe "EMSafe-PLUG-01" de mi vivienda
    Cuando ordeno apagar el enchufe
    Entonces la orden queda en estado "PENDING"
    Y se publica en el tópico "iot/EMSafe-PLUG-01/command"
    Cuando el Edge publica el estado "OFF" con el mismo commandId
    Entonces la orden pasa al estado "CONFIRMED"

  Escenario: Un usuario no puede controlar un enchufe ajeno
    Dado el enchufe "EMSafe-PLUG-01" de otro cliente
    Cuando ordeno apagar el enchufe
    Entonces la solicitud es rechazada
