myProps = [:]
defineProperties()

if (args.length == 0) {
	println ""
	println "The syntax of the command is incorrect."
	println "Usage: groovy -D<property=value> sendSoap [url] [xmlFileName]"
	System.exit(1)
}

//printProperties()

if (args[0] == 'help') {
	println "Usage: groovy -D<property=value> sendSoap [url] [xmlFileName]"
	System.exit(0)
}

url         = args[0]
xmlFileName = args[1] 

url += "/com.eibus.web.soap.Gateway.wcp?timeout=${myProps.timeout}"

if (myProps.saml_authentication == "true") { checkAuthentication() }
if (myProps.organization) { url += "&organization=${myProps.organization}" }
if (myProps.receiver)     { url += "&receiver=${myProps.receiver}" }

sendSoapRequestFromXmlFile()


// ----------------------------------------------------------------------------------------------
// -- HELPER METHODS
// ----------------------------------------------------------------------------------------------

// @override
def println (String arg) {
	System.out.println "${myProps.log_indent_characters}${arg}"
}

def defineProperties() {
	def prop = [:]
	prop['sendsoap.saml.authentication']='true'
	prop['sendsoap.saml.username']=''
	prop['sendsoap.saml.password']=''
	prop['sendsoap.timeout']='30000'
	prop['sendsoap.organization']=''
	prop['sendsoap.check.soapfault']='false'
	prop['sendsoap.request.to.console']='false'
	prop['sendsoap.response.to.file']='false'
	prop['sendsoap.response.to.console']='false'
	prop['sendsoap.receiver']=''
	prop['sendsoap.log.indent.characters']=''
		
	prop.putAll(System.getenv())
	prop.putAll(System.getProperties())
	
	prop2 = [:]
	prop.each { if (it.key =~ /sendsoap\..*/){prop2[it.key.replace('sendsoap.','')] = it.value } }
	prop2.each { myProps[it.key.replace('.','_')] = it.value }
}

def printProperties() {
	println "===== PROPERTIES ====="
	myProps.each {property -> println "sendsoap.${property}" }
	println ""
}

String getSAMLArtifact() {
	return """
	<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
	<SOAP:Header>
		<wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
			<wsse:UsernameToken xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
				<wsse:Username>$myProps.saml_username</wsse:Username>
				<wsse:Password>$myProps.saml_password</wsse:Password>
			</wsse:UsernameToken>
		</wsse:Security>
	</SOAP:Header>
	<SOAP:Body>
		<samlp:Request RequestID="a58bb16b9c-a457-e80b-5037-6ab4c74c741" IssueInstant="2008-01-03T20:19:07Z" MinorVersion="1" MajorVersion="1" xmlns:samlp="urn:oasis:names:tc:SAML:1.0:protocol">
			<samlp:AuthenticationQuery>
				<saml:Subject xmlns:saml="urn:oasis:names:tc:SAML:1.0:assertion">
					<saml:NameIdentifier Format="urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified">$myProps.saml_username</saml:NameIdentifier>
				</saml:Subject>
			</samlp:AuthenticationQuery>
		</samlp:Request>
	</SOAP:Body>
	</SOAP:Envelope>
	"""
}

def sendSoapRequestFromXmlFile() {
	def xml = new File(xmlFileName)
	def response = sendSoap(xml.text,xmlFileName)
	renderFormattedXml(response)
	checkForSoapFault(response)
	return response;
}

def sendSoap(xml,soapmessage) {
	if (myProps.request_to_console == "true") {
		println "SOAP Request message: $xml"
	}
	if (myProps.saml_authentication == "false") {
		def user = myProps.saml_username
		def pw = myProps.saml_password
		Authenticator.setDefault(new Authenticator() {
			PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(user,pw.toCharArray())
			}
		})
	}
	def soapUrl = new URL(url)
	def connection = soapUrl.openConnection()
	connection.setRequestMethod("POST")
	connection.setRequestProperty("Content-Type","application/soap+xml")
	connection.doOutput = true
	Writer writer = new OutputStreamWriter(connection.outputStream)
	writer.write(xml)
	writer.flush()
	writer.close()
	connection.connect()
	
	if (connection.responseCode == 200) {
		return connection.content.text
	} else {
		response = connection.getErrorStream().getText()
		if (soapmessage != "getSAMLArtifact") { checkForSoapFault(response) }
		return response
	}
}

def checkAuthentication() {
	response = sendSoap(getSAMLArtifact(),"getSAMLArtifact")
	renderFormattedXml(response)
	samlArtifact = extractSamlArtifact()
	url += "&SAMLart=" + URLEncoder.encode(samlArtifact,"US-ASCII")
}

def extractSamlArtifact() {
	def soapNS = new groovy.xml.Namespace("http://schemas.xmlsoap.org/soap/envelope/", "SOAP")
	def samlProtocalNS = new groovy.xml.Namespace("urn:oasis:names:tc:SAML:1.0:protocol", "samlp")
	def root = new XmlParser().parseText(response)
	def AssertionArtifact = root[soapNS.Body][samlProtocalNS.Response][samlProtocalNS.AssertionArtifact]
	
	return AssertionArtifact[0].text()
}

def renderFormattedXml(String xml) {
	def node = new XmlParser().parseText(xml)
    def stringWriter = new StringWriter()
	new XmlNodePrinter(new PrintWriter(stringWriter)).print(node)
	
	if (myProps.response_to_console == "true") {
		println "SOAP Response Message : "
		println stringWriter.toString()
	}
	if (myProps.response_to_file == "true")	{
		def responseOutputFile = new File("Response" + xmlFileName)
		responseOutputFile.write(stringWriter.toString())
	}
}

def checkForSoapFault(String xml) {
	if (myProps.check_soapfault == "true") {
		def soapNS = new groovy.xml.Namespace("http://schemas.xmlsoap.org/soap/envelope/", "SOAP")
		def root = new XmlParser().parseText(xml)
		def soapFault = root[soapNS.Body][soapNS.Fault]
		
		if (soapFault.size() > 0) {
			if (myProps.response_to_console == "false" && myProps.response_to_file == "false") {
				myProps.response_to_console = "true"
				renderFormattedXml(xml)
			}
			println "SOAP FAULT occurred!"
			println ""
			System.exit(1)
		}
	}
}