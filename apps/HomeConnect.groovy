/**
 *  Copyright 2022
 *
 *  
*   Based on the original work done by https://github.com/Wattos/hubitat
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Home Connect Integration (APP for Home Conection API integration)
 *
 *  Author: Craig Dewar (craigde)
 *  Date: 2025-11-24
 *  Version 1.0 - Rewrote oauth connection code to fix race condition / page refresh issue and improve initial connection logic
 *  Version: 1.1 - Added support for ProgramAborted event
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field static String messageBuffer = ""
@Field static Integer messageScopeCount = 0

definition(
    name: 'Home Connect Integration',
    namespace: 'craigde',
    author: 'Craig Dewar',
    description: 'Integrates with Home Connect',
    category: 'My Apps',
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

@Field HomeConnectAPI = HomeConnectAPI_create(oAuthTokenFactory: {return getOAuthAuthToken()}, language: {return getLanguage()});
@Field Utils = Utils_create();
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]
def driverVer() { return "3.5" }

//  ===== Settings =====
private getClientId() { settings.clientId }
private getClientSecret() { settings.clientSecret }

//  ===== Lifecycle methods ====
def installed() {
    Utils.toLogger("info", "installing Home Connect")
    synchronizeDevices();
}

def uninstalled() {
    Utils.toLogger("info", "uninstalled Home Connect")
    deleteChildDevicesByDevices(getChildDevices());
}

def updated() {	
    Utils.toLogger("info", "updating with settings")
    synchronizeDevices();
}

//  ===== Helpers =====
def getHomeConnectAPI() {
    return HomeConnectAPI;
}

def getUtils() {
    return Utils;
}

//  ===== Pages =====
preferences {
    page(name: "pageIntro")
    page(name: "pageAuthentication")
    page(name: "pageDevices")
}

def pageIntro() {
    Utils.toLogger("debug", "Showing Introduction Page");

    def countries = HomeConnectAPI.getSupportedLanguages();
    def countriesList = Utils.toFlattenMap(countries);
    if (region != null) {
		atomicState.langCode = region
        Utils.toLogger("debug", "atomicState.langCode: ${region}")
		atomicState.countryCode = countriesList.find { it.key == region}?.value
        Utils.toLogger("debug", "atomicState.countryCode(${atomicState.countryCode})")
    }

    return dynamicPage(
        name: 'pageIntro',
        title: 'Home Connect Introduction', 
        nextPage: 'pageAuthentication',
        install: false, 
        uninstall: true) {
        section("""\
                    |This application connects to the Home Connect service.
                    |It will allow you to monitor your smart appliances from Home Connect within Hubitat.
                    |
                    |Please note, before you can proceed, you will need to:
                    | 1. Sign up at <a href="https://developer.home-connect.com/" target='_blank'>Home Connect Developer Portal</a>.
                    | 2. Go to <a href="https://developer.home-connect.com/applications" target='_blank'>Home Connect Applications</a>.
                    | 3. Register a new application with the following values:
                    |    * <b>Application ID</b>: hubitat-homeconnect-integration
                    |    * <b>OAuth Flow</b>: Authorization Code Grant Flow
                    |    * <b>Redirect URI</b>: ${getFullApiServerUrl()}/oauth/callback
                    |    * You can leave the rest as blank
                    | 4. Copy the following values down below.
                    | 5. Wait 30 minutes before pressing the 'Next' button on this page (this is needed on the HomeConnect side to register your application above on their servers)
                    |""".stripMargin()) {}
            section('Enter your Home Connect Developer Details.') {
                input name: 'clientId', title: 'Client ID', type: 'text', required: true
                input name: 'clientSecret', title: 'Client Secret', type: 'text', required: true
       			input "region", "enum", title: "Select your region", options: countriesList, required: true
       			input "logLevel", "enum", title: "Log Level", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
            }
            section('''\
                    |Press 'Next' to connect to your Home Connect Account if 30 minutes have passed.
                    |'''.stripMargin()) {}
    }
}

//def pageAuthentication() {
//    Utils.toLogger("debug", "Showing Authentication Page");

 //   if (!atomicState.accessToken) {
//        atomicState.accessToken = createAccessToken();
//    }

//    return dynamicPage(
//        name: 'pageAuthentication', 
//        title: 'Home Connect Authentication',
//        nextPage: 'pageDevices',
//        install: false, 
//        uninstall: false) {
//        section() {
//            def title = "Connect to Home Connect"
//            if (atomicState.oAuthAuthToken) {
//                Utils.showHideNextButton(true);
//                title = "Re-connect to Home Connect"
//                paragraph '<b>Success!</b> You are connected to Home Connect. Please press the Next button.'
//            } else {
//                Utils.showHideNextButton(false);
//                paragraph 'To continue, you need to connect your hubitat to Home connect. Please press the button below to connect'
//            }
            
//            href url: generateOAuthUrl(), style:'external', required:false, 'title': title
//        }
//    }
//}
def pageAuthentication() {
    Utils.toLogger("debug", "Showing Authentication Page");

    if (!atomicState.accessToken) {
        atomicState.accessToken = createAccessToken();
    }

    return dynamicPage(
        name: 'pageAuthentication', 
        title: 'Home Connect Authentication',
        nextPage: 'pageDevices',
        install: false, 
        uninstall: false,
        refreshInterval: 0) {  // Add this to prevent auto-refresh
        section() {
            def title = "Connect to Home Connect"
            if (atomicState.oAuthAuthToken) {
                Utils.showHideNextButton(true);
                title = "Re-connect to Home Connect"
                paragraph '<b>Success!</b> You are connected to Home Connect. Please press the Next button.'
            } else {
                Utils.showHideNextButton(false);
                paragraph 'To continue, you need to connect your hubitat to Home connect. Please press the button below to connect'
            }
            
            href url: generateOAuthUrl(), style:'external', required:false, title: title, description: "Tap to connect to Home Connect"
        }
    }
}
def pageDevices() {
    HomeConnectAPI.getHomeAppliances() { devices -> homeConnectDevices = devices}
    def deviceList = [:]
	state.foundDevices = []
	homeConnectDevices.each {
        deviceList << ["${it.haId}":"${it.name} (${it.type}) (${it.haId})"]
		state.foundDevices << [haId: it.haId, name: it.name, type: it.type]
	}

    return dynamicPage(
        name: 'pageDevices', 
        title: 'Home Connect Devices',
        install: true, 
        uninstall: true) {
        section() {
            paragraph 'Select the following devices';
            input name: 'devices', title: 'Select Devices', type: 'enum', required: true, multiple:true, options: deviceList
        }
    }
}

// ==== App behaviour ====

def synchronizeDevices() {
    def childDevices = getChildDevices();
    def childrenMap = childDevices.collectEntries {
        [ "${it.deviceNetworkId}": it ]
    };

    for (homeConnectDeviceId in settings.devices) {
        def hubitatDeviceId = homeConnectIdToDeviceNetworkId(homeConnectDeviceId);

        if (childrenMap.containsKey(hubitatDeviceId)) {
            childrenMap.remove(hubitatDeviceId)
            continue;
        }

        def homeConnectDevice = state.foundDevices.find({it.haId == homeConnectDeviceId})
        switch(homeConnectDevice.type) {
            case "CleaningRobot":
                device = addChildDevice('rferrazguimaraes', 'Home Connect CleaningRobot', hubitatDeviceId);
            break
            case "CookProcessor":
                device = addChildDevice('rferrazguimaraes', 'Home Connect CookProcessor', hubitatDeviceId);
            break
            case "Dishwasher":
                device = addChildDevice('rferrazguimaraes', 'Home Connect Dishwasher', hubitatDeviceId);
            break
            case "Dryer":
                device = addChildDevice('rferrazguimaraes', 'Home Connect Dryer', hubitatDeviceId);
            break
            case "Washer":
                device = addChildDevice('rferrazguimaraes', 'Home Connect Washer', hubitatDeviceId);
            break
            case "WasherDryer":
                device = addChildDevice('rferrazguimaraes', 'Home Connect WasherDryer', hubitatDeviceId);
            break
            case "Freezer":
            case "FridgeFreezer":
            case "Refrigerator":
                device = addChildDevice('rferrazguimaraes', 'Home Connect FridgeFreezer', hubitatDeviceId);
            break
            case "CoffeeMaker":
                device = addChildDevice('rferrazguimaraes', 'Home Connect CoffeeMaker', hubitatDeviceId);
            break
            case "Hood":
                device = addChildDevice('rferrazguimaraes', 'Home Connect Hood', hubitatDeviceId);
            break
            case "Hob":
                device = addChildDevice('rferrazguimaraes', 'Home Connect Hob', hubitatDeviceId);
            break
            case "Oven":
                device = addChildDevice('rferrazguimaraes', 'Home Connect Oven', hubitatDeviceId);
            break
            case "WineCooler":
                device = addChildDevice('rferrazguimaraes', 'Home Connect WineCooler', hubitatDeviceId);
            break
            
            default:
                Utils.toLogger("error", "Not supported: ${homeConnectDevice.type}");
            break
        }
    }

    deleteChildDevicesByDevices(childrenMap.values());
}

def deleteChildDevicesByDevices(devices) {
    for (d in devices) {
        deleteChildDevice(d.deviceNetworkId);
    }
}

def homeConnectIdToDeviceNetworkId(haId) {
    return "${haId}"
}

def intializeStatus(device, boolean checkActiveProgram = true) {
    def haId = device.deviceNetworkId
    
    Utils.toLogger("info", "Initializing the status of the device ${haId}")
    HomeConnectAPI.getStatus(haId) { status ->
        device.deviceLog("info", "Status received: ${status}")
        sendEventToDevice(device, status);
    }
    
    HomeConnectAPI.getSettings(haId) { settings ->
        device.deviceLog("info", "Settings received: ${settings}")
        sendEventToDevice(device, settings);
    }

    if(checkActiveProgram) {
        try {
            HomeConnectAPI.getActiveProgram(haId) { activeProgram ->
                device.deviceLog("info", "ActiveProgram received: ${activeProgram}")
                processMessage(device, activeProgram);
            }
        } catch (Exception e) {
            // no active program
            if(device.getDataValue("ActiveProgram") != "") {
                device.sendEvent(name: "ActiveProgram", value: "newStat", descriptionText: "Active Program changed to: ${newStat}", displayed: true, isStateChange: true)
            }
        }
    }
}

def startProgram(device, programKey, optionKey = "") {
    Utils.toLogger("debug", "startProgram device: ${device}")

    HomeConnectAPI.setActiveProgram(device.deviceNetworkId, programKey, optionKey) { availablePrograms ->
        Utils.toLogger("info", "setActiveProgram availablePrograms: ${availablePrograms}")
    }
}

def stopProgram(device) {
    Utils.toLogger("debug", "stopProgram device: ${device}")

    HomeConnectAPI.setStopProgram(device.deviceNetworkId) { availablePrograms ->
        Utils.toLogger("info", "stopProgram availablePrograms: ${availablePrograms}")
    }
}

def setPowertate(device, boolean state) {
    Utils.toLogger("debug", "setPowertate from ${device} - ${state}")

    HomeConnectAPI.setSettings(device.deviceNetworkId, "BSH.Common.Setting.PowerState", state ? "BSH.Common.EnumType.PowerState.On" : "BSH.Common.EnumType.PowerState.Off") { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

def setLighting(device, boolean state) {
    Utils.toLogger("debug", "setLighting from ${device} - ${state}")

    HomeConnectAPI.setSettings(device.deviceNetworkId, "Cooking.Common.Setting.Lighting", state) { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

def setLightingBrightness(device, value) {
    Utils.toLogger("debug", "setLightingBrightness from ${device} - ${value}")

    HomeConnectAPI.setSettings(device.deviceNetworkId, "Cooking.Common.Setting.LightingBrightness", value) { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

def setAmbientLightEnabled(device, boolean state) {
    Utils.toLogger("debug", "setAmbientLightEnabled from ${device} - ${state}")

    HomeConnectAPI.setSettings(device.deviceNetworkId, "BSH.Common.Setting.AmbientLightEnabled", state) { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

def setAmbientLightBrightness(device, value) {
    Utils.toLogger("debug", "setAmbientLightBrightness from ${device} - ${value}")

    HomeConnectAPI.setSettings(device.deviceNetworkId, "BSH.Common.Setting.AmbientLightBrightness", value) { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

def setVentingLevel(device, level) {
    Utils.toLogger("debug", "setVentingLevel from ${device} - ${level}")
    
    HomeConnectAPI.setSettings(device.deviceNetworkId, "Cooking.Common.Option.Hood.VentingLevel", "Cooking.Hood.EnumType.Stage." + "${level}") { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

def setIntensiveLevel(device, level) {
    Utils.toLogger("debug", "setIntensiveLevel from ${device} - ${level}")
    HomeConnectAPI.setSettings(device.deviceNetworkId, "Cooking.Common.Option.Hood.IntensiveLevel", "Cooking.Hood.EnumType.IntensiveStage." + "${level}") { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

def getActiveProgramOption(device, optionKey) {
    Utils.toLogger("debug", "getActiveProgramOption device: ${device}")
    def availableActiveProgramOptionList = []

    HomeConnectAPI.getActiveProgramOption(device.deviceNetworkId, optionKey) { availableProgramOptions ->
        Utils.toLogger("info", "getActiveProgramOption availableActiveProgramOptionList: ${availableProgramOptions}")
        availableActiveProgramOptionList = availableProgramOptions
    }
    
    return availableActiveProgramOptionList    
}

def getAvailableProgramList(device) {
    Utils.toLogger("debug", "getAvailableProgramList device: ${device}")
    def availableProgramList = []
    
    HomeConnectAPI.getAvailablePrograms(device.deviceNetworkId) { availablePrograms ->
        Utils.toLogger("info", "updateAvailableProgramList availablePrograms: ${availablePrograms}")
        availableProgramList = availablePrograms
    }
    
    return availableProgramList
}

def setSelectedProgram(device, programKey, optionKey = "") {
    Utils.toLogger("debug", "setSelectedProgram device: ${device}")
    
    HomeConnectAPI.setSelectedProgram(device.deviceNetworkId, programKey, optionKey) { availablePrograms ->
        Utils.toLogger("info", "setSelectedProgram availablePrograms: ${availablePrograms}")
    }
}

def getAvailableProgramOptionsList(device, programKey) {
    Utils.toLogger("debug", "getAvailableProgramOptionsList device: ${device} - programKey: ${programKey}")
    def availableProgramOptionsList = []
    
    HomeConnectAPI.getAvailableProgram(device.deviceNetworkId, "${programKey}") { availableProgram ->
        Utils.toLogger("debug", "updateAvailableOptionList availableProgram: ${availableProgram}")
        if(availableProgram) {                
            Utils.toLogger("debug", "updateAvailableOptionList availableProgram.options: ${availableProgram.options}")
            availableProgramOptionsList = availableProgram.options
        }
    }
    
    return availableProgramOptionsList
}

def setSelectedProgramOption(device, optionKey, optionValue) {
    Utils.toLogger("debug", "setSelectedProgramOption device: ${device} - optionKey: ${optionKey} - optionValue: ${optionValue}")
    
    HomeConnectAPI.setSelectedProgramOption(device.deviceNetworkId, optionKey, optionValue) { availablePrograms ->
        Utils.toLogger("info", "setSelectedProgramOption availablePrograms: ${availablePrograms}")
    }
}

def processMessage(device, final ArrayList textArrayList) {
    Utils.toLogger("debug", "processMessage from ${device} message array list: ${textArrayList}")

    if (textArrayList == null || textArrayList.isEmpty()) { 
        device.deviceLog("debug", "Ignore eventstream message")
        return
    }
    
    def text = new groovy.json.JsonBuilder(textArrayList).toString()
    Utils.toLogger("debug", "processMessage from ${device} message text: ${text}")
    
    if(!text.contains('items:')) {
        text = "data:{\"items\":" + text + "}"
    }
    
    processMessage(device, text);
}

def processMessage(device, final String originalText) {
    Utils.toLogger("debug", "processMessage from ${device} message: ${originalText}")
    String text = originalText
    
    // EventStream is documented here
    //  https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events#event_stream_format

    // Ignore comment lines from event stream
    if (text == null || text.trim().isEmpty() || text.startsWith(':')) { 
        device.deviceLog("debug", "Ignore eventstream message")
        return
    }
    
    if(text.contains('error')) {
        def jsonMap = new JsonSlurper().parseText(text)
        //Utils.toLogger("debug", "jsonMap: ${jsonMap}")
        text = jsonMap.toMapString().replaceAll("^\\[|\\]\$", "")
        //Utils.toLogger("debug", "text: ${text}")
    } 
    
    // Parse the lines of data.  Expected types are: event, data, id, retry -- all other fields ignored.
    def (String type, String message) = text.split(':', 2)
    Utils.toLogger("debug", "type: ${type}")
    Utils.toLogger("debug", "message: ${message}")
    switch (type) {
        case 'id':
            Utils.toLogger("debug", "Received ID: ${message}")
            break

        case 'data':
            Utils.toLogger("debug", "Received data: ${message}")
            if (!message.isEmpty()) { // empty messages are just KEEP-AlIVE messages
                def result = new JsonSlurper().parseText(message)?.items
                Utils.toLogger("debug", "result: ${result}")
                processData(device, result)
            }
            break

        case 'event':
            Utils.toLogger("debug", "Received event: ${message}")
            def result = text.replaceAll("(^[\\r\\n]+|[\\r\\n]+\$)", "");
            //device.deviceLog("debug", "result: ${result}")
            ArrayList linesAllEntries = result.split('\n')
            def mapping = [:]
            linesAllEntries.eachWithIndex { item, index ->
                def pair = item.split(':', 2)
                if(pair.first()?.contains("data") && pair.last()?.trim()) {
                    Utils.toLogger("debug", "pair.last(): ${pair.last()}")
                    mapping.put(pair.first(), new JsonSlurper().parseText(pair.last()))
                } else {
                    mapping.put(pair.first(), pair.last())
                }
            }
            Utils.toLogger("debug", "mapping: ${mapping}")
            /*Utils.toLogger("debug", "linesAllEntries: ${linesAllEntries}")
            def mapAllEntries = linesAllEntries.collectEntries { entry ->
                def pair = entry.split(':', 2)
                [(pair.first()): pair.last()]
                //Utils.toLogger("debug", "pair: ${pair}")
            }
            Utils.toLogger("debug", "mapAllEntries: ${mapAllEntries}")
            //device.deviceLog("debug", "mapAllEntries: ${mapAllEntries}")
            def mapConverted = [:]
            Map.Entry<String, String> entry = mapAllEntries.entrySet().iterator().next();
            def keys = mapAllEntries.findAll { it.key != entry.getKey() }.collect { it.key }
            mapConverted.put(entry.getValue(), mapAllEntries.subMap(keys))
            Utils.toLogger("debug", "mapConverted: ${mapConverted}")
            processData(device, mapConverted)*/
            processData(device, mapping)
            break

        case 'retry':
            Utils.toLogger("debug", "Received retry: ${message}")
            break

        case 'error':
            Utils.toLogger("debug", "Received error: ${message}")
            def jsonMap = new JsonSlurper().parseText(originalText)
            processData(device, jsonMap)
            break

        default:
            device.deviceLog("debug", "Received unknown data: ${text}")
            break
    }    
}

def processData(device, final dataContainer) {
    Utils.toLogger("debug", "processData: ${device} - ${dataContainer}")

    if(dataContainer instanceof ArrayList) {
        //Utils.toLogger("debug", "It's an ArrayList: ${dataContainer}")
        dataContainer.each {
            sendEventToDevice(device, it.key)
        }
    } else if(dataContainer instanceof Map) {
        //Utils.toLogger("debug", "It's a Map: ${dataContainer}")
        if(dataContainer.containsKey("event")) {
            switch(dataContainer.event) {
                case "STATUS":
                case "NOTIFY":
                case "EVENT":
                    device.deviceLog("info", "Event: ${dataContainer.event} - Data: ${dataContainer.data?.items}")
                    sendEventToDevice(device, dataContainer.data?.items)
                break
                case "KEEP-ALIVE":
                case "CONNECTED":
                case "DISCONNECTED":
                case "PAIRED":
                case "DEPAIRED":                
                    device.deviceLog("info", "Event: ${dataContainer.event}")
                break
                default:
                    device.deviceLog("error", "Event not supported: ${dataContainer}")
                break
            }
        } else if(dataContainer.containsKey("error")) {
            device.deviceLog("error", "Error Key: ${dataContainer.error?.key} - Description: ${Utils.convertErrorMessageTime(dataContainer.error?.description).replaceAll("^\\[|\\]\$", "")}")
        }
    }
}

def sendEventToDevice(device, final data) {
    Utils.toLogger("debug", "sendEventToDevice: ${device} - ${data}")
    
    data.each {
        switch(it.key) {
            case "BSH.Common.Root.ActiveProgram":
                device.sendEvent(name: "ActiveProgram", value: "${it.displayvalue}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Root.SelectedProgram":
                device.sendEvent(name: "SelectedProgram", value: "${it.displayvalue}", displayed: true, isStateChange: true)
                device.updateSetting("selectedProgram", [value:"${it.displayvalue}", type:"enum"])
            break
            case "BSH.Common.Status.DoorState":
                device.sendEvent(name: "DoorState", value: "${it.displayvalue}", displayed: true, isStateChange: true)
                device.sendEvent(name: "contact", value: "${it.displayvalue?.toLowerCase()}")
            break
		//updated 1.1
		case "BSH.Common.Status.OperationState":
    		def operationState = it.value?.substring(it.value?.lastIndexOf(".")+1)
    		device.sendEvent(name: "OperationState", value: "${operationState}", displayed: true, isStateChange: true)
    
   			 // Reset timers when operation returns to Ready or Inactive state
    		if (operationState == "Ready" || operationState == "Inactive") {
        		device.sendEvent(name: "RemainingProgramTime", value: "00:00", displayed: true, isStateChange: true)
        		device.sendEvent(name: "ProgramProgress", value: "0%", displayed: true, isStateChange: true)
        		device.sendEvent(name: "ElapsedProgramTime", value: "00:00", displayed: true, isStateChange: true)}
			break
            case "BSH.Common.Status.LocalControlActive":
                device.sendEvent(name: "LocalControlActive", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Status.RemoteControlActive":
                device.sendEvent(name: "RemoteControlActive", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Status.RemoteControlStartAllowed":
                device.sendEvent(name: "RemoteControlStartAllowed", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Setting.PowerState":
                device.sendEvent(name: "PowerState", value: "${it.displayvalue}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Setting.TemperatureUnit":
                device.sendEvent(name: "TemperatureUnit", value: "${it.displayvalue?.substring(it.displayvalue?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Setting.ChildLock":
                device.sendEvent(name: "ChildLock", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Setting.AlarmClock":
                device.sendEvent(name: "AlarmClock", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Option.RemainingProgramTime":
                device.sendEvent(name: "RemainingProgramTime", value: "${Utils.convertSecondsToTime(it.value)}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Option.ElapsedProgramTime":
                device.sendEvent(name: "ElapsedProgramTime", value: "${Utils.convertSecondsToTime(it.value)}", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Option.Duration":
                device.sendEvent(name: "Duration", value: "${Utils.convertSecondsToTime(it.value)}", displayed: true, isStateChange: true)
            break                
            case "BSH.Common.Option.ProgramProgress":
                device.sendEvent(name: "ProgramProgress", value: "${it.value}%", displayed: true, isStateChange: true)
            break
            case "BSH.Common.Option.StartInRelative":
                device.sendEvent(name: "StartInRelative", value: "${Utils.convertSecondsToTime(it.value)}", displayed: true, isStateChange: true)
            break

      //added / updated in 1.1      
             case "BSH.Common.Event.ProgramFinished":
   				   device.sendEvent(name: "EventPresentState", value: "${it.displayvalue}", displayed: true, isStateChange: true)
    			   // Reset remaining time when program finishes
   				   device.sendEvent(name: "RemainingProgramTime", value: "00:00", displayed: true, isStateChange: true)
    			   device.sendEvent(name: "ProgramProgress", value: "0%", displayed: true, isStateChange: true)
			 break
			 case "BSH.Common.Event.ProgramAborted":
 				   device.sendEvent(name: "EventPresentState", value: "${it.displayvalue}", displayed: true, isStateChange: true)
   				   device.sendEvent(name: "ProgramAborted", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
   				   // Reset remaining time when program is aborted
   				   device.sendEvent(name: "RemainingProgramTime", value: "00:00", displayed: true, isStateChange: true)
    			   device.sendEvent(name: "ProgramProgress", value: "0%", displayed: true, isStateChange: true)
			  break
            
            case "Dishcare.Dishwasher.Option.IntensivZone":
                device.sendEvent(name: "IntensivZone", value: "${it.value}", displayed: true, isStateChange: true)
                device.updateSetting("${it.name.replaceAll("\\s","")}", [value:"${it.value}", type:"bool"])
            break
            case "Dishcare.Dishwasher.Option.BrillianceDry":
                device.sendEvent(name: "BrillianceDry", value: "${it.value}", displayed: true, isStateChange: true)
                device.updateSetting("${it.name.replaceAll("\\s","")}", [value:"${it.value}", type:"bool"])
            break
            case "Dishcare.Dishwasher.Option.VarioSpeedPlus":
                device.sendEvent(name: "VarioSpeedPlus", value: "${it.value}", displayed: true, isStateChange: true)
                device.updateSetting("${it.name.replaceAll("\\s","")}", [value:"${it.value}", type:"bool"])
            break
            case "Dishcare.Dishwasher.Option.SilenceOnDemand":
                device.sendEvent(name: "SilenceOnDemand", value: "${it.value}", displayed: true, isStateChange: true)
                device.updateSetting("${it.name.replaceAll("\\s","")}", [value:"${it.value}", type:"bool"])
            break
            case "Dishcare.Dishwasher.Option.HalfLoad":
                device.sendEvent(name: "HalfLoad", value: "${it.value}", displayed: true, isStateChange: true)
                device.updateSetting("${it.name.replaceAll("\\s","")}", [value:"${it.value}", type:"bool"])
            break
            case "Dishcare.Dishwasher.Option.ExtraDry":
                device.sendEvent(name: "ExtraDry", value: "${it.value}", displayed: true, isStateChange: true)
                device.updateSetting("${it.name.replaceAll("\\s","")}", [value:"${it.value}", type:"bool"])
            break
            case "Dishcare.Dishwasher.Option.HygienePlus":
                device.sendEvent(name: "HygienePlus", value: "${it.value}", displayed: true, isStateChange: true)
                device.updateSetting("${it.name.replaceAll("\\s","")}", [value:"${it.value}", type:"bool"])
            break
            case "Dishcare.Dishwasher.Event.RinseAidNearlyEmpty":
                device.sendEvent(name: "RinseAidNearlyEmpty", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Dishcare.Dishwasher.Event.SaltNearlyEmpty":
                device.sendEvent(name: "SaltNearlyEmpty", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "ConsumerProducts.CoffeeMaker.Event.BeanContainerEmpty":
                device.sendEvent(name: "BeanContainerEmpty", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "ConsumerProducts.CoffeeMaker.Event.WaterTankEmpty":
                device.sendEvent(name: "WaterTankEmpty", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "ConsumerProducts.CoffeeMaker.Event.DripTrayFull":
                device.sendEvent(name: "DripTrayFull", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Cooking.Common.Option.Hood.VentingLevel":
                device.sendEvent(name: "VentingLevel", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Cooking.Common.Option.Hood.IntensiveLevel":
                device.sendEvent(name: "IntensiveLevel", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Cooking.Common.Setting.LightingBrightness":
                device.sendEvent(name: "LightingBrightness", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "Cooking.Common.Setting.Lighting":
                device.sendEvent(name: "Lighting", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "Cooking.Oven.Status.CurrentCavityTemperature":
                device.sendEvent(name: "CurrentCavityTemperature", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "Cooking.Oven.Setting.SabbathMode":
            case "Refrigeration.Common.Setting.SabbathMode":
                device.sendEvent(name: "SabbathMode", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "Refrigeration.Common.Setting.FreshMode":
                device.sendEvent(name: "FreshMode", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "Refrigeration.Common.Setting.VacationMode":
                device.sendEvent(name: "VacationMode", value: "${it.value}", displayed: true, isStateChange: true)
            break
            case "Refrigeration.FridgeFreezer.Event.DoorAlarmFreezer":
                device.sendEvent(name: "DoorAlarmFreezer", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Refrigeration.FridgeFreezer.Event.DoorAlarmRefrigerator":
                device.sendEvent(name: "DoorAlarmRefrigerator", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Refrigeration.FridgeFreezer.Event.TemperatureAlarmFreezer":
                device.sendEvent(name: "TemperatureAlarmFreezer", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "ConsumerProducts.CleaningRobot.Event.EmptyDustBoxAndCleanFilter":
                device.sendEvent(name: "EmptyDustBoxAndCleanFilter", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "ConsumerProducts.CleaningRobot.Event.RobotIsStuck":
                device.sendEvent(name: "RobotIsStuck", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "ConsumerProducts.CleaningRobot.Event.DockingStationNotFound":
                device.sendEvent(name: "RobotIsStuck", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "LaundryCare.Washer.Event.IDos1FillLevelPoor":
                device.sendEvent(name: "IDos1FillLevelPoor", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "LaundryCare.Washer.Event.IDos2FillLevelPoor":
                device.sendEvent(name: "IDos2FillLevelPoor", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Cooking.Common.Event.Hood.GreaseFilterMaxSaturationNearlyReached":
                device.sendEvent(name: "GreaseFilterMaxSaturationNearlyReached", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "Cooking.Common.Event.Hood.GreaseFilterMaxSaturationReached":
                device.sendEvent(name: "GreaseFilterMaxSaturationReached", value: "${it.value?.substring(it.value?.lastIndexOf(".")+1)}", displayed: true, isStateChange: true)
            break
            case "error":
                device.sendEvent(name: "LastErrorMessage", value: "${Utils.convertErrorMessageTime(it.value?.description)}", displayed: true)
            break
            default:
                device.deviceLog("error", "Message not supported: (${it})")
            break
        }
    }
}

//TODO: Move out into helper library
// ===== Authentication =====
// See Home Connect Developer documentation here: https://developer.home-connect.com/docs/authorization/flow
private final OAUTH_AUTHORIZATION_URL() { 'https://api.home-connect.com/security/oauth/authorize' }
private final OAUTH_TOKEN_URL() { 'https://api.home-connect.com/security/oauth/token' }
private final ENDPOINT_APPLIANCES() { '/api/homeappliances' }

mappings {
    path("/oauth/callback") {action: [GET: "oAuthCallback"]};
}

def generateOAuthUrl() {
    def timestamp = now().toString()
    def stateValue = generateSecureState(timestamp)
    
    Utils.toLogger("debug", "Generated OAuth state with timestamp: ${timestamp}")
    
    def params = [
        'client_id': getClientId(),
        'redirect_uri': getOAuthRedirectUrl(),
        'response_type': 'code',
        'scope': 'IdentifyAppliance Monitor Settings Control',
        'state': stateValue
    ];
    return "${OAUTH_AUTHORIZATION_URL()}?${Utils.toQueryString(params)}";
}
def generateHash(String message) {
    // Simple but effective hash using Java's built-in hashCode
    return message.hashCode().toString()
}
def generateSecureState(timestamp) {
    // Create a state value that includes timestamp and can be validated without storage
    // Format: timestamp:hash, but we'll encode it to avoid special characters
    def message = "${timestamp}:${getClientId()}:${getClientSecret()}"
    def hash = generateHash(message)
    def stateValue = "${timestamp}:${hash}"
    
    // Base64 encode to avoid special characters causing URL parsing issues
    return stateValue.bytes.encodeBase64().toString()
}

def validateSecureState(stateValue) {
    try {
        // Decode the Base64 state
        def decoded = new String(stateValue.decodeBase64())
        
        def parts = decoded.split(':')
        if (parts.length != 2) {
            Utils.toLogger("error", "Invalid state format")
            return false
        }
        
        def timestamp = parts[0]
        def receivedHash = parts[1]
        
        // Check if timestamp is within acceptable range (10 minutes)
        def stateAge = now() - timestamp.toLong()
        if (stateAge < 0 || stateAge > 600000) {
            Utils.toLogger("error", "State timestamp is too old or invalid: ${stateAge}ms")
            return false
        }
        
        // Regenerate hash and compare
        def message = "${timestamp}:${getClientId()}:${getClientSecret()}"
        def expectedHash = generateHash(message)
        
        if (expectedHash != receivedHash) {
            Utils.toLogger("error", "State hash does not match. Expected: ${expectedHash}, Received: ${receivedHash}")
            return false
        }
        
        return true
    } catch (Exception e) {
        Utils.toLogger("error", "Error validating state: ${e}")
        return false
    }
}

def getOAuthRedirectUrl() {
    return "${getFullApiServerUrl()}/oauth/callback?access_token=${atomicState.accessToken}";
}

def oAuthCallback() {
    Utils.toLogger("debug", "Received oAuth callback")
    Utils.toLogger("debug", "Callback params: ${params}")

    def code = params.code
    def oAuthState = params.state
    
    if (!code) {
        Utils.toLogger("error", "No authorization code received in callback")
        return renderOAuthFailure()
    }
    
    if (!oAuthState) {
        Utils.toLogger("error", "No state received in callback")
        return renderOAuthFailure()
    }
    
    // Validate state without needing to store it
    if (!validateSecureState(oAuthState)) {
        Utils.toLogger("error", "Invalid state received in callback: ${oAuthState}")
        return renderOAuthFailure()
    }
    
    Utils.toLogger("info", "State validation successful")
    
    // Clear OAuth tokens (no state to clear!)
    atomicState.oAuthRefreshToken = null
    atomicState.oAuthAuthToken = null
    atomicState.oAuthTokenExpires = null

    acquireOAuthToken(code)

    if (!atomicState.oAuthAuthToken) {
        Utils.toLogger("error", "Failed to acquire OAuth token")
        return renderOAuthFailure()
    }
    
    Utils.toLogger("info", "OAuth authentication successful")
    renderOAuthSuccess()
}

def acquireOAuthToken(String code) {
    Utils.toLogger("debug", "Acquiring OAuth Authentication Token");
    apiRequestAccessToken([
        'grant_type': 'authorization_code',
        'code': code,
        'client_id': getClientId(),
        'client_secret': getClientSecret(),
        'redirect_uri': getOAuthRedirectUrl(),
    ]);
}

def refreshOAuthToken() {
    Utils.toLogger("debug", "Refreshing OAuth Authentication Token");
    apiRequestAccessToken([
        'grant_type': 'refresh_token',
        'refresh_token': atomicState.oAuthRefreshToken,
        'client_secret': getClientSecret(),
    ]);
}

def apiRequestAccessToken(body) {
    try {
        httpPost(uri: OAUTH_TOKEN_URL(), requestContentType: 'application/x-www-form-urlencoded', body: body) { response ->
            if (response && response.data && response.success) {
                atomicState.oAuthRefreshToken = response.data.refresh_token
                atomicState.oAuthAuthToken = response.data.access_token
                atomicState.oAuthTokenExpires = now() + (response.data.expires_in * 1000)
            } else {
                log.error "Failed to acquire OAuth Authentication token. Response was not successful."
            }
        }
    } catch (e) {
        log.error "Failed to acquire OAuth Authentication token due to Exception: ${e}"
    }
}

def getOAuthAuthToken() {
    // Expire the token 1 minute before to avoid race conditions
    if(now() >= atomicState.oAuthTokenExpires - 60_000) {
        refreshOAuthToken();
    }
    
    return atomicState.oAuthAuthToken;
}

def getLanguage() {
    return atomicState.langCode;
}

def renderOAuthSuccess() {
    render contentType: 'text/html', data: '''
    <p>Your Home Connect Account is now connected to Hubitat</p>
    <p>Close this window to continue setup.</p>
    '''
}

def renderOAuthFailure() {
    render contentType: 'text/html', data: '''
        <p>Unable to connect to Home Connect. You can see the logs for more information</p>
        <p>Close this window to try again.</p>
    '''
}

/**
 * The Home Connect API
 *
 * The complete documentation can be found here: https://apiclient.home-connect.com/#/
 */
def HomeConnectAPI_create(Map params = [:]) {
    def defaultParams = [
        apiUrl: 'https://api.home-connect.com',
        language: 'en-US',
        oAuthTokenFactory: null
    ]

    def resolvedParams = defaultParams << params;
    def apiUrl = resolvedParams['apiUrl']
    def oAuthTokenFactory = resolvedParams['oAuthTokenFactory']
    def language = resolvedParams['language']

    def instance = [:];
    def json = new JsonSlurper();

    def authHeaders = {
        return ['Authorization': "Bearer ${oAuthTokenFactory()}", 'Accept-Language': "${language()}", 'accept': "application/vnd.bsh.sdk.v1+json"]
    }

     def apiGet = { path, closure ->
        Utils.toLogger("debug", "API Get Request to Home Connect - uri: ${apiUrl + path}")
        try {
            return httpGet(uri: apiUrl + path,
                           contentType: "application/json",
                           'headers': authHeaders()) { response -> 
                Utils.toLogger("debug", "API Get response.data - ${response.data}")
                if(response.data)
                {
                    closure(response.data);
                }
            }
        } catch (groovyx.net.http.HttpResponseException e) {
            if(path.contains('programs/active') && !path.contains('programs/active/options')) {
                // exception case when there is no program active at the moment so just ignore the error here and handle it inside the method intializeStatus
                throw new Exception("\"${path}\"")
            } else {
                Utils.toLogger("error", "apiGet HttpResponseException - error: ${e.getResponse()?.getData()} - path: ${path}")
            }            
        } catch (e)	{
            Utils.toLogger("error", "apiGet - error: ${e} - path: ${path}")
        }
    };
         
    def apiPut = { path, data, closure ->
        Utils.toLogger("debug", "API Put Request to Home Connect - uri: ${apiUrl + path}")
        Utils.toLogger("debug", "API Put original - ${data}")
        String body = new groovy.json.JsonBuilder(data).toString()
        Utils.toLogger("debug", "API Put converted - ${body}")

        try {
            return httpPut(uri: apiUrl + path,
                           contentType: "application/json",
                           requestContentType: "application/json",
                           body: body,
                           headers: authHeaders()) { response -> 
                Utils.toLogger("debug", "API Put response.data - ${response.data}")
                if(response.data)
                {
                    closure(response.data);
                }
            }
        } catch (groovyx.net.http.HttpResponseException e) {
            Utils.toLogger("error", "apiPut HttpResponseException - error: ${e.getResponse().getData()} - path: ${path} - body: ${body}")
        } catch (e)	{
            Utils.toLogger("error", "apiPut - error: ${e} - path: ${path} - body: ${body}")
        }
    };

    def apiDelete = { path, closure ->
        Utils.toLogger("debug", "API Delete Request to Home Connect - uri: ${apiUrl + path}")
        
        try {
            return httpDelete(uri: apiUrl + path,
                           contentType: "application/json",
                           requestContentType: "application/json",
                           headers: authHeaders()) { response -> 
                Utils.toLogger("debug", "API Delete response.data - ${response.data}")
                if(response.data)
                {
                    closure(response.data);
                }
            }
        } catch (groovyx.net.http.HttpResponseException e) {
            Utils.toLogger("error", "apiDelete HttpResponseException - error: ${e.getResponse().getData()} - path: ${path}")
        } catch (e)	{
            Utils.toLogger("error", "apiDelete - error: ${e} - path: ${path}")
        }
    };

    /**
     * Get all home appliances which are paired with the logged-in user account.
     *
     * This endpoint returns a list of all home appliances which are paired
     * with the logged-in user account. All paired home appliances are returned
     * independent of their current connection atomicState. The connection state can
     * be retrieved within the field 'connected' of the respective home appliance.
     * The haId is the primary access key for further API access to a specific
     * home appliance.
     *
     * Example return value:
     * [
     *    {
     *      "name": "My Bosch Oven",
     *      "brand": "BOSCH",
     *      "vib": "HNG6764B6",
     *      "connected": true,
     *      "type": "Oven",
     *      "enumber": "HNG6764B6/09",
     *      "haId": "BOSCH-HNG6764B6-0000000011FF"
     *    }
     * ]
     */
    instance.getHomeAppliances = { closure ->
        Utils.toLogger("info", "Retrieving Home Appliances from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}") { response ->
            closure.call(response.data.homeappliances)
        }
    };

    /**
     * Get a specfic home appliances which are paired with the logged-in user account.
     *
     * This endpoint returns a specific home appliance which is paired with the
     * logged-in user account. It is returned independent of their current
     * connection atomicState. The connection state can be retrieved within the field
     * 'connected' of the respective home appliance.
     * The haId is the primary access key for further API access to a specific
     * home appliance.
     *
     * Example return value:
     *
     * {
     *   "name": "My Bosch Oven",
     *   "brand": "BOSCH",
     *   "vib": "HNG6764B6",
     *   "connected": true,
     *   "type": "Oven",
     *   "enumber": "HNG6764B6/09",
     *   "haId": "BOSCH-HNG6764B6-0000000011FF"
     * }
     */
    instance.getHomeAppliance = { haId, closure ->
        Utils.toLogger("info", "Retrieving Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}") { response ->
            closure.call(response.data)
        }
    };

    /**
     * Get all programs of a given home appliance.
     *
     * Example return value:
     *
     * [
     *   {
     *     "key": "Cooking.Oven.Program.HeatingMode.HotAir",
     *     "constraints": {
     *       "available": true,
     *       "execution": "selectandstart"
     *     }
     *   },
     *   {
     *     "key": "Cooking.Oven.Program.HeatingMode.TopBottomHeating",
     *     "constraints": {
     *       "available": true,
     *       "execution": "selectandstart"
     *     }
     *   },
     *   {
     *     "key": "Cooking.Oven.Program.HeatingMode.PizzaSetting",
     *     "constraints": {
     *       "available": true,
     *       "execution": "selectonly"
     *     }
     *   }
     * ]
     */
    instance.getPrograms = { haId, closure ->
        Utils.toLogger("info", "Retrieving All Programs of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs") { response ->
            closure.call(response.data.programs)
        }
    };

    /**
     * Get all programs which are currently available on the given home appliance.
     *
     * Example return value:
     *
     * [
     *   {
     *     "key": "Cooking.Oven.Program.HeatingMode.HotAir",
     *     "constraints": {
     *       "available": true,
     *       "execution": "selectandstart"
     *     }
     *   },
     *   {
     *     "key": "Cooking.Oven.Program.HeatingMode.TopBottomHeating",
     *     "constraints": {
     *       "available": true,
     *       "execution": "selectandstart"
     *     }
     *   },
     *   {
     *     "key": "Cooking.Oven.Program.HeatingMode.PizzaSetting",
     *     "constraints": {
     *       "available": true,
     *       "execution": "selectonly"
     *     }
     *   }
     * ]
     */
    instance.getAvailablePrograms = { haId, closure ->
        Utils.toLogger("info", "Retrieving All Programs of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/available") { response ->
            closure.call(response.data.programs)
        }
    };

    /**
     * Get specific available program.
     *
     * Example return value:
     *
     * {
     *     "key": "Cooking.Oven.Program.HeatingMode.HotAir",
     *     "options": [
     *       {
     *         "key": "Cooking.Oven.Option.SetpointTemperature",
     *         "type": "Int",
     *         "unit": "°C",
     *         "constraints": {
     *           "min": 30,
     *           "max": 250
     *         }
     *       },
     *       {
     *         "key": "BSH.Common.Option.Duration",
     *         "type": "Int",
     *         "unit": "seconds",
     *         "constraints": {
     *           "min": 1,
     *           "max": 86340
     *         }
     *       }
     *     ]
     * }
     */
    instance.getAvailableProgram = { haId, programKey, closure ->
        Utils.toLogger("info", "Retrieving the '${programKey}' Program of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/available/${programKey}") { response ->
            closure.call(response.data)
        }
    };

    /**
     * Get program which is currently executed.
     *
     * Example return value:
     *
     * {
     *     "key": "Cooking.Oven.Program.HeatingMode.HotAir",
     *     "options": [
     *       {
     *         "key": "Cooking.Oven.Option.SetpointTemperature",
     *         "value": 230,
     *         "unit": "°C"
     *       },
     *       {
     *         "key": "BSH.Common.Option.Duration",
     *         "value": 1200,
     *         "unit": "seconds"
     *       }
     *     ]
     * }
     */
    instance.getActiveProgram = { haId, closure ->
        Utils.toLogger("info", "Retrieving the active Program of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/active") { response ->
            closure.call(response.data)
        }
    };
        
    instance.setActiveProgram = { haId, programKey, options = "", closure ->
        def data = [key: "${programKey}"]
        if (options != "") {
            data.put("options", options)
        } 
        Utils.toLogger("info", "Set the active program '${programKey}' of Home Appliance '$haId' from Home Connect")
        apiPut("${ENDPOINT_APPLIANCES()}/${haId}/programs/active", [data: data]) { response ->
            closure.call(response.data)
        }
    };        

    instance.setStopProgram = { haId, closure ->
        Utils.toLogger("info", "Stop the active program of Home Appliance '$haId' from Home Connect")
        apiDelete("${ENDPOINT_APPLIANCES()}/${haId}/programs/active") { response ->
            closure.call(response.data)
        }
    };  

    /**
     * Get all options of the active program like temperature or duration.
     *
     * Example return value:
     *
     * {
     *     "key": "Cooking.Oven.Program.HeatingMode.HotAir",
     *     "options": [
     *       {
     *         "key": "Cooking.Oven.Option.SetpointTemperature",
     *         "value": 230,
     *         "unit": "°C"
     *       },
     *       {
     *         "key": "BSH.Common.Option.Duration",
     *         "value": 1200,
     *         "unit": "seconds"
     *       }
     *     ]
     *   }
     */
    instance.getActiveProgramOptions = { haId, closure ->
        Utils.toLogger("info", "Retrieving the active Program Options of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/active/options") { response ->
            //Utils.toLogger("info", "getActiveProgramOptions of Home Appliance '$haId' from Home Connect ${response.data}")
            closure.call(response.data.options)
        }
    };

    /**
     * Get one specific option of the active program, e.g. the duration.
     *
     * Example return value:
     *
     * {
     *  "key": "Cooking.Oven.Option.SetpointTemperature",
     *  "value": 180,
     *  "unit": "°C"
     * }
     */
    instance.getActiveProgramOption = { haId, optionKey, closure ->
        Utils.toLogger("info", "Retrieving the active Program Option '${optionKey}' of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/active/options/${optionKey}") { response ->
            closure.call(response.data.options)
        }
    };
        
    instance.setActiveProgramOption = { haId, optionKey, value, unit = "", closure ->
        def data = [key: "${optionKey}", value: "${value}"]
        if (unit != "") {
            data.put("unit", unit)
        }
        Utils.toLogger("info", "Retrieving the active Program Option '${optionKey}' of Home Appliance '$haId' from Home Connect")
        apiPut("${ENDPOINT_APPLIANCES()}/${haId}/settings/${settingsKey}", [data: data]) { response ->
            closure.call(response.data)
        }
    };

    /**
     * Get the program which is currently selected.
     *
     * In most cases the selected program is the program which is currently shown on the display of the home appliance.
     * This program can then be manually adjusted or started on the home appliance itself. 
     * 
     * Example return value:
     *
     * {
     *     "key": "Cooking.Oven.Program.HeatingMode.HotAir",
     *     "options": [
     *       {
     *         "key": "Cooking.Oven.Option.SetpointTemperature",
     *         "value": 230,
     *         "unit": "°C"
     *       },
     *       {
     *         "key": "BSH.Common.Option.Duration",
     *         "value": 1200,
     *         "unit": "seconds"
     *       }
     *     ]
     * }
     */
    instance.getSelectedProgram = { haId, closure ->
        Utils.toLogger("info", "Retrieving the selected Program of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/selected") { response ->
            closure.call(response.data)
        }
    };

    instance.setSelectedProgram = { haId, programKey, options = "", closure ->
        def data = [key: "${programKey}"]
        if (options != "") {
            data.put("options", options)
        } 
        Utils.toLogger("info", "Set the selected program '${programKey}' of Home Appliance '$haId' from Home Connect")
        apiPut("${ENDPOINT_APPLIANCES()}/${haId}/programs/selected", [data: data]) { response ->
            closure.call(response.data)
        }
    };        

    /**
     * Get all options of selected program.
     *
     * Example return value:
     *
     * {
     *     "key": "Cooking.Oven.Program.HeatingMode.HotAir",
     *     "options": [
     *       {
     *         "key": "Cooking.Oven.Option.SetpointTemperature",
     *         "value": 230,
     *         "unit": "°C"
     *       },
     *       {
     *         "key": "BSH.Common.Option.Duration",
     *         "value": 1200,
     *         "unit": "seconds"
     *       }
     *     ]
     *   }
     */
    instance.getSelectedProgramOptions = { haId, closure ->
        Utils.toLogger("info", "Retrieving the selected Program Options of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/selected/options") { response ->
            closure.call(response.data.options)
        }
    };

    /**
     * Get specific option of selected program
     *
     * Example return value:
     *
     * {
     *  "key": "Cooking.Oven.Option.SetpointTemperature",
     *  "value": 180,
     *  "unit": "°C"
     * }
     */
    instance.getSelectedProgramOption = { haId, optionKey, closure ->
        Utils.toLogger("info", "Retrieving the selected Program Option ${optionKey} of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/programs/selected/options/${optionKey}") { response ->
            closure.call(response.data.options)
        }
    };

    instance.setSelectedProgramOption = { haId, optionKey, optionValue, closure ->
        def data = [key: "${optionKey}", value: optionValue]
        Utils.toLogger("info", "Retrieving the selected Program Option ${optionKey} of Home Appliance '$haId' from Home Connect")
        apiPut("${ENDPOINT_APPLIANCES()}/${haId}/programs/selected/options/${optionKey}", [data: data]) { response ->
            closure.call(response.data)
        }
    };     

    /**
     * Get current status of home appliance
     *
     * A detailed description of the available status can be found here:
     *
     * https://developer.home-connect.com/docs/api/status/remotecontrolactivationstate - Remote control activation state
     * https://developer.home-connect.com/docs/api/status/remotestartallowancestate - Remote start allowance state
     * https://developer.home-connect.com/docs/api/status/localcontrolstate - Local control state
     * https://developer.home-connect.com/docs/status/operation_state - Operation state
     * https://developer.home-connect.com/docs/status/door_state - Door state
     *
     * Several more device-specific states can be found at https://developer.home-connect.com/docs/api/status/remotecontrolactivationatomicState.
     *
     * Example return value:
     *
     * [
     *  {
     *    "key": "BSH.Common.Status.OperationState",
     *    "value": "BSH.Common.EnumType.OperationState.Ready"
     *  },
     *  {
     *    "key": "BSH.Common.Status.LocalControlActive",
     *    "value": true
     *  }
     * ]
     */
    instance.getStatus = { haId, closure ->
        Utils.toLogger("info", "Retrieving the status of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/status") { response ->
            closure.call(response.data.status)
        }
    };

    /**
     * Get current status of home appliance
     *
     * A detailed description of the available status can be found here:
     *
     * https://developer.home-connect.com/docs/api/status/remotecontrolactivationstate - Remote control activation state
     * https://developer.home-connect.com/docs/api/status/remotestartallowancestate - Remote start allowance state
     * https://developer.home-connect.com/docs/api/status/localcontrolstate - Local control state
     * https://developer.home-connect.com/docs/status/operation_state - Operation state
     * https://developer.home-connect.com/docs/status/door_state - Door state
     *
     * Several more device-specific states can be found at https://developer.home-connect.com/docs/api/status/remotecontrolactivationatomicState.
     *
     * Example return value:
     *
     *  {
     *    "key": "BSH.Common.Status.OperationState",
     *    "value": "BSH.Common.EnumType.OperationState.Ready"
     *  }
     */
    instance.getSingleStatus = { haId, statusKey, closure ->
        Utils.toLogger("info", "Retrieving the status '${statusKey}' of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/status/${statusKey}") { response ->
            closure.call(response.data)
        }
    };

    /**
     * Get a list of available settings
     *
     * Get a list of available setting of the home appliance.
     * Further documentation can be found here:
     *
     *  https://developer.home-connect.com/docs/settings/power_state - Power state
     *  https://developer.home-connect.com/docs/api/settings/fridgetemperature - Fridge temperature
     *  https://developer.home-connect.com/docs/api/settings/fridgesupermode - Fridge super mode
     *  https://developer.home-connect.com/docs/api/settings/freezertemperature - Freezer temperature
     *  https://developer.home-connect.com/docs/api/settings/freezersupermode - Freezer super mode
     *
     * Example return value:
     *
     * [
     *   {
     *     "key": "BSH.Common.Setting.PowerState",
     *     "value": "BSH.Common.EnumType.PowerState.On"
     *   },
     *   {
     *     "key": "Refrigeration.FridgeFreezer.Setting.SuperModeFreezer",
     *     "value": true
     *   }
     * ]
     */
    instance.getSettings = { haId, closure ->
        Utils.toLogger("info", "Retrieving the settings of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/settings") { response ->
            closure.call(response.data.settings)
        }
    };

    /**
     * Get a specific setting
     *
     *
     * Example return value:
     *
     * {
     *   "key": "BSH.Common.Setting.PowerState",
     *   "value": "BSH.Common.EnumType.PowerState.On",
     *   "type": "BSH.Common.EnumType.PowerState",
     *   "constraints": {
     *     "allowedvalues": [
     *       "BSH.Common.EnumType.PowerState.On",
     *       "BSH.Common.EnumType.PowerState.Standby"
     *     ],
     *     "access": "readWrite"
     *   }
     * }
     */
    instance.getSetting = { haId, settingsKey, closure ->
        Utils.toLogger("info", "Retrieving the setting '${settingsKey}' of Home Appliance '$haId' from Home Connect")
        apiGet("${ENDPOINT_APPLIANCES()}/${haId}/settings/${settingsKey}") { response ->
            closure.call(response.data)
        }
    };

    instance.setSettings = { haId, settingsKey, value, closure ->
        Utils.toLogger("info", "Set the setting '${settingsKey}' of Home Appliance '$haId' from Home Connect")
        apiPut("${ENDPOINT_APPLIANCES()}/${haId}/settings/${settingsKey}", [data: [key: "${settingsKey}", value: value]]) { response ->
            closure.call(response.data)
        }
    };

    /**
     * Get stream of events for one appliance
     *
     * NOTE: This can only be done from within a device driver. It will not work within an app
     */
    instance.connectDeviceEvents = { haId, interfaces -> 
        Utils.toLogger("info", "Connecting to the event stream of Home Appliance '$haId' from Home Connect")
        //Utils.toLogger("info", "authHeaders '${authHeaders()}' ")
        interfaces.eventStream.connect(
            "${apiUrl}${ENDPOINT_APPLIANCES()}/${haId}/events",
            [rawData: true,
             rawStream: true,
             ignoreSSLIssues: true,
             headers: ([ 'Accept': 'text/event-stream' ] << authHeaders())])
    };

    /**
     * stop stream of events for one appliance
     *
     * NOTE: This can only be done from within a device driver. It will not work within an app
     */
    instance.disconnectDeviceEvents = { haId, interfaces -> 
        Utils.toLogger("info", "Disconnecting to the event stream of Home Appliance '$haId' from Home Connect")
        interfaces.eventStream.close()
    };
        
    /**
     * Get stream of events for all appliances 
     *
     * NOTE: This can only be done from within a device driver. It will not work within an app
     */
    instance.connectEvents = { interfaces -> 
        Utils.toLogger("info", "Connecting to the event stream of all Home Appliances from Home Connect")
        interfaces.eventStream.connect(
            "${apiUrl}/api/homeappliances/events",
            [rawData: true,
             rawStream: true,
             ignoreSSLIssues: true,
             headers: ([ 'Accept': 'text/event-stream' ] << authHeaders())])
    };

    instance.getSupportedLanguages = {
        Utils.toLogger("info", "Getting the list of supported languages")
        // Documentation: https://api-docs.home-connect.com/general?#supported-languages
        return ["Bulgarian": ["Bulgaria": "bg-BG"], 
                "Chinese (Simplified)": ["China": "zh-CN", "Hong Kong": "zh-HK", "Taiwan, Province of China": "zh-TW"], 
                "Czech": ["Czech Republic": "cs-CZ"], 
                "Danish": ["Denmark": "da-DK"],
                "Dutch": ["Belgium": "nl-BE", "Netherlands": "nl-NL"],
                "English": ["Australia": "en-AU", "Canada": "en-CA", "India": "en-IN", "New Zealand": "en-NZ", "Singapore": "en-SG", "South Africa": "en-ZA", "United Kingdom": "en-GB", "United States": "en-US"],
                "Finnish": ["Finland": "fi-FI"],
                "French": ["Belgium": "fr-BE", "Canada": "fr-CA", "France": "fr-FR", "Luxembourg": "fr-LU", "Switzerland": "fr-CH"],
                "German": ["Austria": "de-AT", "Germany": "de-DE", "Luxembourg": "de-LU", "Switzerland": "de-CH"],
                "Greek": ["Greece": "el-GR"],
                "Hungarian": ["Hungary": "hu-HU"],
                "Italian": ["Italy": "it-IT", "Switzerland": "it-CH"],
                "Norwegian": ["Norway": "nb-NO"],
                "Polish": ["Poland": "pl-PL"],
                "Portuguese": ["Portugal": "pt-PT"],
                "Romanian": ["Romania": "ro-RO"],
                "Russian": ["Russian Federation": "ru-RU"],
                "Serbian": ["Suriname": "sr-SR"],
                "Slovak": ["Slovakia": "sk-SK"],
                "Slovenian": ["Slovenia": "sl-SI"],
                "Spanish": ["Chile": "es-CL", "Peru": "es-PE", "Spain": "es-ES"],
                "Swedish": ["Sweden": "sv-SE"],
                "Turkish": ["Turkey": "tr-TR"],
                "Ukrainian": ["Ukraine": "uk-UA"]
               ]
    };
    
    return instance;
}

/**
 * Simple utilities for manipulation
 */

def Utils_create() {
    def instance = [:];
    
    instance.toQueryString = { Map m ->
    	return m.collect { k, v -> "${k}=${new URI(null, null, v.toString(), null)}" }.sort().join("&")
    }

    instance.toFlattenMap = { Map m ->
    	return m.collectEntries { k, v -> 
            def flattened = [:]
            if (v instanceof Map) {
                instance.toFlattenMap(v).collectEntries {  k1, v1 -> 
                    flattened << [ "${v1}": "${k} - ${k1} (${v1})"];
                } 
            } else {
                flattened << [ "${k}": v ];
            }
            return flattened;
        } 
    }

    instance.toLogger = { level, msg ->
        if (level && msg) {
            Integer levelIdx = LOG_LEVELS.indexOf(level);
            Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel);
            if (setLevelIdx < 0) {
                setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL);
            }
            if (levelIdx <= setLevelIdx) {
                log."${level}" "${app.name} ${msg}";
            }
        }
    }
    
    // Converts seconds to time hh:mm:ss
    instance.convertSecondsToTime = { sec ->
                                     long millis = sec * 1000
                                     long hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(millis)
                                     long minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis) % java.util.concurrent.TimeUnit.HOURS.toMinutes(1)
                                     String timeString = String.format("%02d:%02d", Math.abs(hours), Math.abs(minutes))
                                     return timeString
    }
    
    instance.extractInts = { String input ->
                            return input.findAll( /\d+/ )*.toInteger()
    }
    
    instance.convertErrorMessageTime = { String input ->
        Integer valueInteger = instance.extractInts(input).last()
        String valueStringConverted = instance.convertSecondsToTime(valueInteger)
        return input.replaceAll( valueInteger.toString() + " seconds", valueStringConverted )
    }        
    
  //  instance.showHideNextButton = { show ->
//	    if(show) paragraph "<script>\$('button[name=\"_action_next\"]').show()</script>";
//	    else paragraph "<script>\$('button[name=\"_action_next\"]').hide()</script>";
//    }
    
instance.showHideNextButton = { show ->
    if(show) paragraph "<script>if(typeof jQuery !== 'undefined'){\$('button[name=\"_action_next\"]').show();}</script>"
    else paragraph "<script>if(typeof jQuery !== 'undefined'){\$('button[name=\"_action_next\"]').hide();}</script>"
}
    
    return instance;
}
