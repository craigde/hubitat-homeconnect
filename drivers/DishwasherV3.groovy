/**
 *  Home Connect Dishwasher v3 Driver
 *
 *  Author: Craig Dewar (craigde)
 *  Architecture: v3 unified event routing
 *
 *  - No SSE logic in driver
 *  - Parent handles all event parsing and routing
 *  - Driver implements parseEvent(evt) for device-specific logic
 *  - deviceNetworkId = HC3-<haId>
 */

import groovy.json.JsonSlurper

metadata {
    definition(name: "Home Connect Dishwasher v3", namespace: "craigde", author: "Craig Dewar") {

        capability "Initialize"
        capability "Refresh"
        capability "Switch"

        // Dishwasher-specific attributes
        attribute "operationState", "string"
        attribute "doorState", "string"
        attribute "remainingProgramTime", "string"
        attribute "elapsedProgramTime", "string"
        attribute "programProgress", "number"
        attribute "activeProgram", "string"
        attribute "selectedProgram", "string"

        // Options
        attribute "IntensivZone", "boolean"
        attribute "BrillianceDry", "boolean"
        attribute "VarioSpeedPlus", "boolean"
        attribute "SilenceOnDemand", "boolean"
        attribute "HalfLoad", "boolean"
        attribute "ExtraDry", "boolean"
        attribute "HygienePlus", "boolean"

        // Events
        attribute "RinseAidNearlyEmpty", "string"
        attribute "SaltNearlyEmpty", "string"

        // Commands
        command "startProgram", [[name:"Program Key*", type:"STRING"]]
        command "stopProgram"
        command "setPower", [[name:"On/Off*", type:"ENUM", constraints:["on","off"]]]
        command "setProgramOption", [
            [name:"Option Key*", type:"STRING"],
            [name:"Value*", type:"STRING"]
        ]
    }
}

def installed() {
    log.info "Installed Home Connect Dishwasher v3"
}

def updated() {
    log.info "Updated Home Connect Dishwasher v3"
}

def initialize() {
    log.info "Initializing dishwasher v3"
    parent.intializeStatus(device)
}

def refresh() {
    log.info "Refreshing dishwasher v3"
    parent.intializeStatus(device)
}

/* ============================================================
   COMMANDS
   ============================================================ */

def startProgram(String programKey) {
    log.info "Starting program: ${programKey}"
    parent.startProgram(device, programKey)
}

def stopProgram() {
    log.info "Stopping program"
    parent.stopProgram(device)
}

def setPower(String state) {
    boolean on = (state == "on")
    log.info "Setting power: ${on}"
    parent.setPowerState(device, on)
}

def setProgramOption(String optionKey, String value) {
    log.info "Setting program option ${optionKey} = ${value}"
    parent.setSelectedProgramOption(device, optionKey, value)
}

/* ============================================================
   EVENT ENTRY POINT (v3 architecture)
   ============================================================ */

/**
 * Parent calls this for every event item:
 *
 * evt = [
 *   haId: "403060520614003484",
 *   key: "Dishcare.Dishwasher.Option.IntensivZone",
 *   value: true,
 *   displayvalue: "True"
 * ]
 */
def parseEvent(Map evt) {
    if (!evt?.key) return

    log.debug "parseEvent: ${evt}"

    switch (evt.key) {

        /* ===== Core Dishwasher Status ===== */

        case "BSH.Common.Status.OperationState":
            def state = extractEnum(evt.value)
            sendEvent(name: "operationState", value: state)
        break

        case "BSH.Common.Status.D
