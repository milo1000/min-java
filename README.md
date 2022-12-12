## Microcontroller Interconnect Network protocol version 2.0

This MIN repository includes java library implementation of the protocol
MIN protocol.
See the Wiki for further details:

http://github.com/min-protocol/min/wiki

There is based on python reference implementation:

https://github.com/min-protocol/min


All software and documentation available under the terms of the MIT License.

File structure:

	/pl.skifosoft.miprotocol Java library implementation

	/
		Example.java         Simple send/receive example
		SerialInterface.java COM port interface implemented using jSerialCom library

SerialInterface.java is NOT part of library, it's example how to implement interface for communication
with serial port, but can be used "as is" in your own project.
This one uses https://github.com/Fazecast/jSerialComm which I highly recommend,
but one can choose any other implementation for that purpose.
