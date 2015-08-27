@Grapes([
	@Grab(group='org.slf4j', module='slf4j-api', version='1.6.1'),
	@Grab(group='ch.qos.logback', module='logback-classic', version='0.9.28')
])

import groovy.util.logging.Slf4j;
import groovy.util.slurpersupport.GPathResult;


import groovy.xml.XmlUtil;

import org.codehaus.groovy.runtime.InvokerHelper;

@Slf4j

/**
 * This script will configure the JMS Connector after Process Suite installation.
 * Steps :
 * # Gets the Process Suite Installation information like organization, version , build number.
 * # Deploys the 'OpenText JMS Connector' package
 * # Creates JMS Configuration in the XML store.
 * # Creates the JMS Service Group , Service Container with the above Configuration.
 * 
 * Pre-requisite :
 * # JAVA needs to be installed and JAVA_HOME environment variable should be set 
 * # Groovy needs to be installed and GROOVY_HOME environment variable should be set
 * # Process Platform needs to be installed and CORDYS_HOME environment variable should be set
 * # ActiveMQ needs to be installed and MQ_HOME environment variable should be set
 * # It need the following environment variables to be set 'cordys.host','cordys.user','cordys.pwd'  
 */
class build extends Script {
	def prop = ['orgDN':'','version':'','build':''];
	
	
	def packages = ['OpenText JMS Connector'];
	def hostName = System.getenv("cordys.host")
	def cordysUser = System.getenv("cordys.user")
	def cordysPwd = System.getenv("cordys.pwd")
	def undeploy = false;
	def configurationName = "junitconfiguration"
	def activeMQHome = System.getenv("MQ_HOME")
	
	
	String aboutCordys = """
	<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
		<SOAP:Body>
			<GetInstallationInfo xmlns="http://schemas.cordys.com/1.0/monitor"/>
		</SOAP:Body>
	</SOAP:Envelope>"""

	def run() {
		log.info "Started"
		
		System.setProperty('sendsoap.saml.username', "$cordysUser");
		System.setProperty('sendsoap.saml.password', "$cordysPwd");
		System.setProperty('sendsoap.response.to.console','true');
		System.setProperty('sendsoap.request.to.console','true');
		
		//If validation fails don't proceed further.
		if(validateInputs() == false) {
			return;
		}
		
		doWork()
	}

	def validateInputs()
	{
		if(!hostName) {
			log.error "'cordys.host' is not set. Set this environment variable value to your Process Platform URL. Example : 'http://skothuri4t5zd02/cordys' "
			return false;
		}
		if(!cordysUser) {
			log.error "'cordys.user' is not set. Set this environment variable value to your Process Platform User."
			return false;
		}
		if(!cordysPwd) {
			log.error "'cordys.pwd' is not set. Set this environment variable value to your Process Platform Password."
			return false;
		}
		if(!activeMQHome) {
			log.error "'MQ_HOME' is not set. Set this environment variable pointing to the Active MQ installation directory. "
			return false;
		}
		return true;
	}
	
	def doWork()
	{
		def root = invokeSoapRequest(aboutCordys);
		
		def buildInfo = root.'**'.find {node-> node.name() == 'buildinfo'}
		prop.version = buildInfo.version
		prop.build = buildInfo.build
		
		def monitorDN = root.'**'.find{processor->processor.cn.text().startsWith("monitor@")}.dn.text()
		//Extracting the orgDN from monitor DN
		prop.orgDN = monitorDN.substring(monitorDN.indexOf("cn=soap nodes,")+14)
		
		//Deploying the package
		deployPackage packages
		
		//Create JMS connector test configuration.
		createJMSConfiguration()
		
		//Creating the JMS Service
		createService()
	}
	
	def createJMSConfiguration()
	{
		def root = invokeSoapRequest(getUpdateXmlStroeRequest());
	}
	
	def createService()
	{
		def serviceGroupName = "JMS JUnit"
		String getLDapObject="""
		<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
		  <SOAP:Body>
		    <GetLDAPObject xmlns="http://schemas.cordys.com/1.0/ldap">
		      <dn>cn=$serviceGroupName,cn=soap nodes,$prop.orgDN</dn>
		    </GetLDAPObject>
		  </SOAP:Body>
		</SOAP:Envelope>"""
		def root = invokeSoapRequest(getLDapObject);
		
		//Checking the response contains child or not.
		if(root.'**'.find {node-> node.name().endsWith("Response")}.children().size() !=0)
		{
			log.info "JMS Connector Service is already created"
			return;
		}
		log.info "Creating the JMS Connector Service"
		
		String jmsConnectorCreation = """
		<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
			<SOAP:Body>
				<Update xmlns="http://schemas.cordys.com/1.0/ldap">
					<tuple>
						<new>
							<entry dn="cn=$serviceGroupName,cn=soap nodes,$prop.orgDN">
								<objectclass>
									<string>top</string>
									<string>bussoapnode</string>
								</objectclass>
								<cn>
									<string>$serviceGroupName</string>
								</cn>
								<description>
									<string>JMS</string>
								</description>
								<busmethodsets>
									<string>cn=IntervalPollerServices.IntervalPollerInterface,cn=OpenText JMS Connector,cn=cordys,cn=defaultInst,o=vanenburg.com</string>
									<string>cn=JMSServices.JMSServicesInterface,cn=OpenText JMS Connector,cn=cordys,cn=defaultInst,o=vanenburg.com</string>
								</busmethodsets>
								<labeleduri>
									<string>http://schemas.cordys.com/jmsconnector/intervalpoller/3.0</string>
									<string>http://schemas.cordys.com/jmsconnector/operations/3.0</string>
								</labeleduri>
								<bussoapnodeconfiguration>
									<string>
										&lt;configuration&gt;
											&lt;routing ui_algorithm=&quot;failover&quot; ui_type=&quot;loadbalancing&quot;&gt;
												&lt;numprocessors&gt;100000&lt;/numprocessors&gt;
												&lt;algorithm&gt;com.eibus.transport.routing.DynamicRouting&lt;/algorithm&gt;
											&lt;/routing&gt;
											&lt;validation&gt;
												&lt;protocol&gt;false&lt;/protocol&gt;
												&lt;payload&gt;false&lt;/payload&gt;
											&lt;/validation&gt;
											&lt;IgnoreWhiteSpaces&gt;false&lt;/IgnoreWhiteSpaces&gt;
										&lt;/configuration&gt;
									</string>
								</bussoapnodeconfiguration>
							</entry>
						</new>
					</tuple>
					<tuple>
						<new>
							<entry dn="cn=OpenText JMS Connector,cn=$serviceGroupName,cn=soap nodes,$prop.orgDN">
								<objectclass>
									<string>top</string>
									<string>bussoapprocessor</string>
								</objectclass>
								<cn>
									<string>OpenText JMS Connector</string>
								</cn>
								<computer>
									<string>skothuri4t5zd02</string>
								</computer>
								<busosprocesshost/>
								<bussoapprocessorconfiguration>
									<string>
										&lt;configurations autoStartCount=&quot;&quot;&gt;
											&lt;cancelReplyInterval&gt;30000&lt;/cancelReplyInterval&gt;
											&lt;gracefulCompleteTime&gt;15&lt;/gracefulCompleteTime&gt;
											&lt;abortTime&gt;5&lt;/abortTime&gt;
											&lt;jreconfig&gt;
												&lt;param value=&quot;-cp $activeMQHome\\activemq-all-5.6.0.jar&quot;/&gt;
												&lt;param value=&quot;-Dcom.cordys.xatransaction.TransactionManagerFactory=com.cordys.xatransaction.atomikos.AtomikosTransactionManagerFactory&quot; /&gt;
											&lt;/jreconfig&gt;
											&lt;routing ui_type=&quot;loadbalancing&quot; ui_algorithm=&quot;failover&quot;&gt;
												&lt;preference&gt;1&lt;/preference&gt;
											&lt;/routing&gt;
											&lt;configuration implementation=&quot;com.opentext.jmsconnector.JMSConnector&quot; htmfile=&quot;admin/JMSConfiguration.caf&quot;&gt;
												&lt;classpath xmlns=&quot;http://schemas.cordys.com/1.0/xmlstore&quot;&gt;
													&lt;location&gt;components/jmsconnector/jmsconnector.jar&lt;/location&gt;
													&lt;location&gt;components/jmstransport/jmstransport.jar&lt;/location&gt;
													&lt;location&gt;components/jmsconnector/lib/jms.jar&lt;/location&gt;
												&lt;/classpath&gt;
												&lt;startupDependency xmlns=&quot;http://schemas.cordys.com/1.0/xmlstore&quot; xmlns:cws=&quot;http://schemas.cordys.com/cws/1.0&quot; xmlns:c=&quot;http://schemas.cordys.com/cws/1.0&quot; xmlns:SOAP=&quot;http://schemas.xmlsoap.org/soap/envelope/&quot;&gt;
													&lt;namespace&gt;http://schemas.cordys.com/1.0/xmlstore&lt;/namespace&gt;
												&lt;/startupDependency&gt;
												&lt;configurationfilename&gt;$configurationName&lt;/configurationfilename&gt;
											&lt;/configuration&gt;
										&lt;/configurations&gt;
									</string>
								</bussoapprocessorconfiguration>
								<automaticstart>
									<string>true</string>
								</automaticstart>
							</entry>
						</new>
					</tuple>
					<tuple>
						<new>
							<entry dn="cn=connectionpoint-OpenText JMS Connector,cn=OpenText JMS Connector,cn=$serviceGroupName,cn=soap nodes,$prop.orgDN">
								<objectclass>
									<string>top</string>
									<string>busconnectionpoint</string>
								</objectclass>
								<cn>
									<string>connectionpoint-OpenText JMS Connector</string>
								</cn>
								<labeleduri>
									<string>socket://skothuri4t5zd02:0</string>
								</labeleduri>
								<description>
									<string>
										&lt;socket-configuration/&gt;
									</string>
								</description>
								<clientconnectionpoint>
									<string>false</string>
								</clientconnectionpoint>
							</entry>
						</new>
					</tuple>
				</Update>
			</SOAP:Body>
		</SOAP:Envelope>"""
		invokeSoapRequest(jmsConnectorCreation);
	}
	
	def deployPackage(def packages)
	{
		packages.reverseEach {packageName ->

			log.info "Checking the package '$packageName' status..... "

			String capPackageStatus ="""
		<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
			<SOAP:Body>
				<GetPackagesStatus xmlns="http://schemas.cordys.com/cap/1.0">
					<Space shared="true">
					</Space>
					<Packages>
						<Package>$packageName</Package>
					</Packages>
				</GetPackagesStatus>
			</SOAP:Body>
		</SOAP:Envelope>"""

			String deployCAP = """
		<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
			<SOAP:Body>
				<DeployCAP xmlns="http://schemas.cordys.com/cap/1.0" revertOnFailure="false" Timeout="901000">
					<url>$hostName/system/wcp/capcontent/packages/com.cordys.web.cap.CAPGateway.wcp?capName=$packageName</url>
				</DeployCAP>
			</SOAP:Body>
		</SOAP:Envelope>"""

			def undeployCAP = """
		<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
			<SOAP:Body>
				<UnDeployCAP xmlns="http://schemas.cordys.com/cap/1.0" Timeout="901000">
					<CAP>$packageName</CAP>			
				</UnDeployCAP>
			</SOAP:Body>
		</SOAP:Envelope>"""


			def root = invokeSoapRequest(capPackageStatus);
			def capInfo = root.'**'.find {node-> node.name() == 'Shared'}

			if( capInfo.Status == "DEPLOYED") {
				log.info "The cordys application package : '$packageName' is already deployed"

				if(undeploy)
				{
					log.info "Undeploying the cap..."
					root = invokeSoapRequest(undeployCAP);
					log.info root.'**'.find {node-> node.name() == 'status'}.text()
				}
			}
			else {

				log.info "The cordys application package :'$packageName' is not deployed."
				log.info "Deploying the cap..."
				root = invokeSoapRequest(deployCAP);
				log.info root.'**'.find {node-> node.name() == 'status'}.text()
			}
		}
	}


	def invokeSoapRequest(String requestXml)
	{
		File tmpFile = File.createTempFile("requestXML_",".xml")
		tmpFile.deleteOnExit()
		tmpFile.withWriter { out -> out.writeLine( requestXml ) }

		response = new GroovyShell().run(new File("groovy/soapClient.groovy"),"$hostName" , tmpFile.getAbsolutePath());
		return new XmlSlurper().parseText(response)
	}
	
	
	def getUpdateXmlStroeRequest()
	{
		return """
		<SOAP:Envelope xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/">
			<SOAP:Body>
				<UpdateXMLObject xmlns="http://schemas.cordys.com/1.0/xmlstore">
					<tuple key="OpenText/JMSConnector/$configurationName" level="isv" version="organization" name="$configurationName" isFolder="false" unconditional="true" recursive="false" >
						<new >
							<tns:configuration xmlns:tns="http://schemas.cordys.com/jmsconnector/configuration/3.0">
								<tns:Timeout>10000</tns:Timeout>
								<tns:MessageIDDelimiter>;</tns:MessageIDDelimiter>
								<tns:DisableMessageSelectors>true</tns:DisableMessageSelectors>
								<tns:CheckConnectionOnRequest>true</tns:CheckConnectionOnRequest>
								<tns:JMSPollingInterval>5555</tns:JMSPollingInterval>
								<tns:Charset>UTF8</tns:Charset>
								<tns:DestinationManagers>
									<tns:DestinationManager name="activemq">
										<tns:Destinations>
											<tns:Destination name="OutsideToBPM">
												<tns:Access>both</tns:Access>
												<tns:InboundMessageTrigger>StartBPM</tns:InboundMessageTrigger>
												<tns:Poller xmlns:tns="http://schemas.cordys.com/jmsconnector/configuration/3.0">
													<tns:Type>REQUEST_REPLY_COMPATIBILITY</tns:Type>
													<tns:Configuration>
														<tns:IntervalPoller>
															<tns:PollingInterval>10000</tns:PollingInterval>
															<tns:MaintainSequence>true</tns:MaintainSequence>
															<tns:RaiseProblemWhenBlocked>false</tns:RaiseProblemWhenBlocked>
														</tns:IntervalPoller>
													</tns:Configuration>
												</tns:Poller>
												<tns:ErrorDestination>activemq.BPMErrors</tns:ErrorDestination>
												<tns:JNDIName>OutsideToBPM</tns:JNDIName>
											</tns:Destination>
											<tns:Destination name="DefaultError">
												<tns:Access>both</tns:Access>
												<tns:JNDIName>DefaultError</tns:JNDIName>
												<tns:IsDefaultErrorDest>true</tns:IsDefaultErrorDest>
											</tns:Destination>
										</tns:Destinations>
										<tns:JMSVendor>org.apache.activemq.jndi.ActiveMQInitialContextFactory</tns:JMSVendor>
										<tns:ProviderURL>tcp://localhost:61616</tns:ProviderURL>
										<tns:JNDICFName>activemq-cf</tns:JNDICFName>
										<tns:AuthenticationType>basic</tns:AuthenticationType>
										<tns:ConnectionUser>system</tns:ConnectionUser>
										<tns:ConnectionPassword>bWFuYWdlcg==</tns:ConnectionPassword>
										<tns:GlobalTransaction>false</tns:GlobalTransaction>
									</tns:DestinationManager>
								</tns:DestinationManagers>
								<tns:Triggers>
									<tns:Trigger name="StartBPM">
										<tns:Operation>ExecuteProcess</tns:Operation>
										<tns:Namespace>http://schemas.cordys.com/bpm/execution/1.0</tns:Namespace>
										<tns:OrganizationDN>$prop.orgDN</tns:OrganizationDN>
										<tns:UserDN>cn=$cordysUser,cn=organizational users,$prop.orgDN</tns:UserDN>
									</tns:Trigger>
								</tns:Triggers>
							</tns:configuration>
						</new>
					</tuple>
				</UpdateXMLObject>
			</SOAP:Body>
		</SOAP:Envelope>"""
	}
	static void main(String[] args) {
		InvokerHelper.runScript(build, args)
	}
}
