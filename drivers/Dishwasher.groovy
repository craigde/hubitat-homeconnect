/**
 *  Home Connect Dishwasher (Child Device of Home Connection Integration)
 *
 *  Author: 
 *  Current owner: Craig Dewar (craigde) > 1.7
 *  Contributors:Rangner Ferraz Guimaraes (rferrazguimaraes) for original driver port
 *  
 *
 *  Version history
 *  1.0 - Initial commit
 *  1.1 - Added attributes ProgramProgress and RemainingProgramTime
 *  1.2 - Better handling of STOP events from event stream
 *  1.3 - Updating program when pressing 'Initialize' button
 *  1.4 - Added events for RinseAidNearlyEmpty and SaltNearlyEmpty
 *  1.5 - Added event for StartInRelative
 *  1.6 - Local STATUS sniffing, contact mirroring, bool preference read, JSON .toString()
 *  1.7 - Hubitat update compatibility; also parse Option.* ProgramProgress/RemainingProgramTime and expose remainingTime/remainingTimeDisplay
 *  1.8 - Small fixes
 *  1.9 - Added support for delayed start
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field Utils = Utils_create()
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]
@Field static final Integer eventStreamDisconnectGracePeriod = 30
def driverVer() { return "1.8" }

metadata {
    definition(name: "Home Connect Dishwasher", namespace: "craigde", author: "Craig Dewar") {
        capability "Sensor"
        capability "Switch"
        capability "ContactSensor"
        capability "Initialize"

        command "deviceLog", [[name: "Level*", type:"STRING", description: "Level of the message"],
                              [name: "Message*", type:"STRING", description: "Message"]]
        command "startProgram"
        command "stopProgram"
        command "startProgramDelayed", [
                [name:"delayMinutes*", type:"NUMBER", description:"Minutes from now to start"],
                [name:"program", type:"ENUM", description:"Program to run (optional, defaults to selected program or Normal)", constraints:["Auto","Normal","Heavy","Express","Delicate","Rinse","Machine Care"]]
        ]
        attribute "AvailableProgramsList", "JSON_OBJECT"
        attribute "AvailableOptionsList", "JSON_OBJECT"

        attribute "RemoteControlActive", "enum", ["true", "false"]
        attribute "RemoteControlStartAllowed", "enum", ["true", "false"]

        attribute "OperationState", "enum", [
            "Inactive","Ready","DelayedStart","Run","Pause","ActionRequired","Finished","Error","Aborting"
        ]

        attribute "DoorState", "enum", ["Open","Closed","Locked"]

        attribute "ActiveProgram", "string"
        attribute "SelectedProgram", "string"

        attribute "PowerState", "enum", ["Off","On","Standby"]

        attribute "EventPresentState", "enum", ["Event active","Off","Confirmed"]

        attribute "ProgramProgress", "number"
        attribute "RemainingProgramTime", "string"     // HH:MM display
        attribute "StartInRelative", "string"

        // Common dishwasher options (string attributes so we can show true/false words)
        attribute "IntensivZone", "string"
        attribute "BrillianceDry", "string"
        attribute "VarioSpeedPlus", "string"
        attribute "SilenceOnDemand", "string"
        attribute "HalfLoad", "string"
        attribute "ExtraDry", "string"
        attribute "HygienePlus", "string"
        attribute "RinseAidNearlyEmpty", "string"
        attribute "SaltNearlyEmpty", "string"

        // Extra attributes for Node-RED SVG flow
        attribute "remainingTime", "number"            // seconds
        attribute "remainingTimeDisplay", "string"     // HH:MM

        attribute "EventStreamStatus", "enum", ["connected", "disconnected"]
        attribute "DriverVersion", "string"
    }

    preferences {
        section {
            List<String> availableProgramsList = getAvailableProgramsList()
            if (availableProgramsList.size() != 0) {
                input name:"selectedProgram", type:"enum", title: "Select Program", options: availableProgramsList
            }

            List<String> availableOptionList = getAvailableOptionsList()
            for (int i = 0; i < availableOptionList.size(); ++i) {
                String titleName = availableOptionList[i]
                String optionName = titleName.replaceAll("\\s","")
                input name: optionName, type:"bool", title: "${titleName}", defaultValue: false
            }

            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: true
        }
    }
}

/* ---------- Commands ---------- */

void startProgram() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) {
            parent.startProgram(device, programToSelect.key)
        }
    }
}

void stopProgram() {
    parent.stopProgram(device)
}

void startProgramDelayed(Number delayMinutes, String program = null) {
    // Determine which program to use
    String programToUse = program ?: selectedProgram ?: "Normal"
    
    Utils.toLogger("debug", "startProgramDelayed: delay=${delayMinutes} min, program=${programToUse}")
    
    def programToSelect = state.foundAvailablePrograms.find { it.name == programToUse }
    if (programToSelect) {
        parent.startProgramDelayed(device, programToSelect.key, delayMinutes)
    } else {
        Utils.toLogger("error", "Program '${programToUse}' not found in available programs")
        Utils.toLogger("debug", "Available programs: ${state.foundAvailablePrograms?.collect { it.name }}")
    }
}

void initialize() {
    Utils.toLogger("debug", "initialize()")
    intializeStatus()
}

void installed() {
    Utils.toLogger("debug", "installed()")
    intializeStatus()
}

void updated() {
    Utils.toLogger("debug", "updated()")
    setCurrentProgram()
    updateAvailableOptionsList()
    setCurrentProgramOptions()
}

void uninstalled() {
    disconnectEventStream()
}

/* ---------- Helpers: program & options ---------- */

void setCurrentProgram() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) {
            parent.setSelectedProgram(device, programToSelect.key)
        }
    }
}

void setCurrentProgramOptions() {
    List<String> availableOptionList = getAvailableOptionsList()
    if (!availableOptionList) return

    for (int i = 0; i < availableOptionList.size(); ++i) {
        String optionTitle = availableOptionList[i]
        String optionName  = optionTitle.replaceAll("\\s", "")

        Boolean optionValue = (settings?."${optionName}" ?: false)

        def programOption = state?.foundAvailableProgramOptions?.find { it.name == optionTitle }
        if (programOption) {
            parent.setSelectedProgramOption(device, programOption.key, optionValue)
        } else {
            Utils.toLogger("debug", "Option not found for '${optionTitle}'")
        }
    }
}

void updateAvailableProgramList() {
    state.foundAvailablePrograms = parent.getAvailableProgramList(device)
    Utils.toLogger("debug", "updateAvailableProgramList state.foundAvailablePrograms: ${state.foundAvailablePrograms}")
    def programList = state.foundAvailablePrograms.collect { it.name }
    Utils.toLogger("debug", "getAvailablePrograms programList: ${programList}")
    // .toString() avoids Groovy's ambiguous block warning after Hubitat update
    sendEvent(name:"AvailableProgramsList", value: new groovy.json.JsonBuilder(programList).toString(), displayed: false)
}

void updateAvailableOptionsList() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) {
            state.foundAvailableProgramOptions = parent.getAvailableProgramOptionsList(device, programToSelect.key)
            def programOptionsList = state.foundAvailableProgramOptions.collect { it.name }
            sendEvent(name:"AvailableOptionsList", value: new groovy.json.JsonBuilder(programOptionsList).toString(), displayed: false)
            Utils.toLogger("debug", "updateAvailableOptionList programOptionsList: ${programOptionsList}")
            return
        }
    }

    state.foundAvailableProgramOptions = []
    sendEvent(name:"AvailableOptionsList", value: [], displayed: false)
}

/* ---------- Switch handling ---------- */

def on()  { safeSetPowerState(true)  }
def off() { safeSetPowerState(false) }

private void safeSetPowerState(Boolean val) {
    if (parent.respondsTo('setPowerState')) {
        parent.setPowerState(device, val)
    } else if (parent.respondsTo('setPowertate')) { // legacy typo
        parent.setPowertate(device, val)
    } else {
        Utils.toLogger("error", "Parent has no setPowerState/setPowertate method")
    }
}

/* ---------- Init & Event Stream ---------- */

void intializeStatus() {
    Utils.toLogger("debug", "Initializing the status of the device")

    updateAvailableProgramList()
    updateAvailableOptionsList()
    parent.intializeStatus(device)

    try {
        disconnectEventStream()
        connectEventStream()
    } catch (Exception e) {
        Utils.toLogger("error", "intializeStatus() failed: ${e.message}")
        setEventStreamStatusToDisconnected()
    }
}

void connectEventStream() {
    Utils.toLogger("debug", "connectEventStream()")
    parent.getHomeConnectAPI().connectDeviceEvents(device.deviceNetworkId, interfaces)
}

void reconnectEventStream(Boolean notIfAlreadyConnected = true) {
    Utils.toLogger("debug", "reconnectEventStream(notIfAlreadyConnected=$notIfAlreadyConnected)")
    if (device.currentValue("EventStreamStatus") == "connected" && notIfAlreadyConnected) {
        Utils.toLogger("debug", "already connected; skipping reconnection")
    } else {
        connectEventStream()
    }
}

void disconnectEventStream() {
    Utils.toLogger("debug", "disconnectEventStream()")
    parent.getHomeConnectAPI().disconnectDeviceEvents(device.deviceNetworkId, interfaces)
}

void setEventStreamStatusToConnected() {
    Utils.toLogger("debug", "setEventStreamStatusToConnected()")
    unschedule("setEventStreamStatusToDisconnected")
    if (device.currentValue("EventStreamStatus") == "disconnected") {
        sendEvent(name: "EventStreamStatus", value: "connected", displayed: true, isStateChange: true)
    }
    state.connectionRetryTime = 15
}

void setEventStreamStatusToDisconnected() {
    Utils.toLogger("debug", "setEventStreamStatusToDisconnected()")
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    if (state.connectionRetryTime) {
        state.connectionRetryTime *= 2
        if (state.connectionRetryTime > 900) state.connectionRetryTime = 900
    } else {
        state.connectionRetryTime = 15
    }
    Utils.toLogger("debug", "reconnecting EventStream in ${state.connectionRetryTime} seconds")
    runIn(state.connectionRetryTime, "reconnectEventStream")
}

void eventStreamStatus(String text) {
    Utils.toLogger("debug", "Received eventstream status message: ${text}")
    def (String type, String message) = text.split(':', 2)
    switch (type) {
        case 'START':
            atomicState.oStartTokenExpires = now() + 60_000 // 60 seconds
            setEventStreamStatusToConnected()
            break
        case 'STOP':
            if (now() >= atomicState.oStartTokenExpires) {
                Utils.toLogger("debug", "eventStreamDisconnectGracePeriod: ${eventStreamDisconnectGracePeriod}")
                runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            } else {
                Utils.toLogger("debug", "stream started recently so ignore STOP event")
            }
            break
        default:
            Utils.toLogger("error", "Received unhandled Event Stream status message: ${text}")
            atomicState.oStartTokenExpires = now()
            runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            break
    }
}

/* ---------- Parse incoming SSE lines ---------- */

private String formatHHMMFromSeconds(Integer secs) {
    if (secs == null || secs < 0) secs = 0
    Integer h = (int)(secs / 3600)
    Integer m = (int)((secs % 3600) / 60)
    return "${String.format('%02d', h)}:${String.format('%02d', m)}"
}

void parse(String text) {
    Utils.toLogger("debug", "Received eventstream message: ${text}")

    // Lightweight JSON sniffing so we can immediately reflect common attributes
    try {
        if (text?.startsWith('data:')) {
            String payload = text.substring(5).trim()
            if (payload && payload.startsWith('{')) {
                def obj = new groovy.json.JsonSlurper().parseText(payload)
                def items = (obj?.items instanceof List) ? obj.items : []

                items.each { item ->
                    String key = item?.key as String
                    def valObj = item?.value
                    String valStr = (valObj != null) ? valObj.toString() : null

                    switch (key) {
                        /* --- Door / Contact --- */
                        case 'BSH.Common.Status.DoorState':
                            String pretty = valStr?.tokenize('.')?.last()
                            if (pretty in ['Open','Closed','Locked']) {
                                sendEvent(name: "DoorState", value: pretty, isStateChange: true)
                                if (pretty == 'Open')  sendEvent(name: "contact", value: "open",   isStateChange: true)
                                if (pretty == 'Closed') sendEvent(name: "contact", value: "closed", isStateChange: true)
                            }
                            break

                        /* --- Operation state --- */
                        case 'BSH.Common.Status.OperationState':
                            String op = valStr?.tokenize('.')?.last()
                            if (op) sendEvent(name: "OperationState", value: op, isStateChange: true)
                            break

                        /* --- Power (often reported as a Setting) --- */
                        case 'BSH.Common.Setting.PowerState':
                        case 'BSH.Common.Status.PowerState':
                            String pwr = valStr?.tokenize('.')?.last()
                            if (pwr) sendEvent(name: "PowerState", value: pwr, isStateChange: true)
                            break

                        /* --- Progress (may come as Status.* or Option.*) --- */
                        case 'BSH.Common.Status.ProgramProgress':
                        case 'BSH.Common.Option.ProgramProgress':
                            Integer pct = (valObj instanceof Number) ? (valObj as Integer)
                                        : (valStr?.isInteger() ? valStr.toInteger() : null)
                            if (pct != null) sendEvent(name: "ProgramProgress", value: pct, isStateChange: true)
                            break

                        /* --- Remaining time (may come as Status.* or Option.*) --- */
                        case 'BSH.Common.Status.RemainingProgramTime':
                        case 'BSH.Common.Option.RemainingProgramTime':
                            Integer secs = (valObj instanceof Number) ? (valObj as Integer)
                                       : (valStr?.isInteger() ? valStr.toInteger() : null)
                            if (secs != null) {
                                // Ignore spurious 0 while actively running
                                String op2 = (device.currentValue("OperationState") ?: "")
                                Integer prog2 = ((device.currentValue("ProgramProgress") ?: 0) as Integer)
                                String pwr2 = (device.currentValue("PowerState") ?: "")
                                boolean acceptZero = ['Finished','Inactive','Ready','Error','Aborting'].contains(op2) || prog2 >= 100 || pwr2 == 'Off'
                                if (secs == 0 && !acceptZero) {
                                    Utils.toLogger("debug", "Ignoring transient RemainingProgramTime=0 during Run (op=$op2, prog=$prog2, pwr=$pwr2)")
                                    break
                                }
                                String hhmm = formatHHMMFromSeconds(secs)
                                sendEvent(name: "RemainingProgramTime", value: hhmm, isStateChange: true)
                                // Extras for Node-RED SVG flow
                                sendEvent(name: "remainingTime", value: secs, isStateChange: true)
                                sendEvent(name: "remainingTimeDisplay", value: hhmm, isStateChange: false)
                            }
                            break
                    } // switch
                } // each
            }
        }
    } catch (e) {
        Utils.toLogger("error", "STATUS/OPTION payload parse error: ${e}")
    }

    // Always let the parent handle the full message
    parent.processMessage(device, text)
    sendEvent(name: "DriverVersion", value: driverVer())
}

/* ---------- Misc ---------- */

def deviceLog(level, msg) {
    Utils.toLogger(level, msg)
}

/* ---------- Utilities ---------- */

def Utils_create() {
    def instance = [:]
    instance.toLogger = { level, msg ->
        if (level && msg) {
            Integer levelIdx = LOG_LEVELS.indexOf(level)
            Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
            if (setLevelIdx < 0) setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
            if (levelIdx <= setLevelIdx) {
                log."${level}" "${device.displayName} ${msg}"
            }
        }
    }
    return instance
}

/* ---------- Lists helpers ---------- */

List<String> getAvailableProgramsList() {
    String json = device?.currentValue("AvailableProgramsList")
    if (json != null) return parseJson(json)
    return []
}

List<String> getAvailableOptionsList() {
    String json = device?.currentValue("AvailableOptionsList")
    if (json != null) return parseJson(json)
    return []
}
