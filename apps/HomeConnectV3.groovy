/**
 *  Home Connect Integration v3 (Parent App)
 *
 *  Author: Craig Dewar (craigde)
 *
 *  Version history
 *  3.0 - New architecture:
 *        - New app name (v3), safe to run side-by-side with v1
 *        - New child deviceNetworkId prefix "HC3-<haId>"
 *        - No device-specific event mapping in parent
 *        - Parent exposes handleApplianceEvent(evt) and routes to child.parseEvent(evt)
 *        - OAuth, API wrapper, and Utils lifted from v1 with minimal changes
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field static String messageBuffer = ""
@Field static Integer messageScopeCount = 0

definition(
    name: 'Home Connect Integration v3',
    namespace: 'craigde',
    author: 'Craig Dewar',
    description: 'Integrates with Home Connect (v3 architecture)',
    category: 'My Apps',
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

@Field HomeConnectAPI = HomeConnectAPI_create(
    oAuthTokenFactory: { return getOAuthAuthToken() },
    language: { return getLanguage() }
)
@Field Utils = Utils_create()
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]
def driverVer() { return "3.0" }

// ===== Settings =====
private getClientId()    { settings.clientId }
private getClientSecret(){ settings.clientSecret }

// ===== Lifecycle methods =====

def installed() {
    Utils.toLogger("info", "installing Home Connect v3")
    synchronizeDevices()
}

def uninstalled() {
    Utils.toLogger("info", "uninstalled Home Connect v3")
    deleteChildDevicesByDevices(getChildDevices())
}

def updated() {
    Utils.toLogger("info", "updating with settings")
    synchronizeDevices()
}

// ===== Helpers =====

def getHomeConnectAPI() { return HomeConnectAPI }
def getUtils()          { return Utils }

// ===== Pages =====

preferences {
    page(name: "pageIntro")
    page(name: "pageAuthentication")
    page(name: "pageDevices")
}

def pageIntro() {
    Utils.toLogger("debug", "Showing Introduction Page")

    def countries     = HomeConnectAPI.getSupportedLanguages()
    def countriesList = Utils.toFlattenMap(countries)

    if (region != null) {
        atomicState.langCode   = region
        atomicState.countryCode = countriesList.find { it.key == region }?.value
        Utils.toLogger("debug", "atomicState.langCode: ${region}")
        Utils.toLogger("debug", "atomicState.countryCode: ${atomicState.countryCode}")
    }

    return dynamicPage(
        name: 'pageIntro',
        title: 'Home Connect Introduction (v3)',
        nextPage: 'pageAuthentication',
        install: false,
        uninstall: true
    ) {
        section("") {
            paragraph """\
This application connects to the Home Connect service.
It will allow you to monitor your smart appliances from Home Connect within Hubitat.

Before you proceed, you need to:

1. Sign up at the Home Connect Developer Portal.
2. Create an application with:
   - Application ID: hubitat-homeconnect-integration
   - OAuth Flow: Authorization Code Grant Flow
   - Redirect URI: ${getFullApiServerUrl()}/oauth/callback
3. Copy Client ID and Client Secret into the fields below.
4. Wait ~30 minutes before pressing Next (Home Connect-side propagation).
"""
        }
        section('Enter your Home Connect Developer Details.') {
            input name: 'clientId',      title: 'Client ID',      type: 'text', required: true
            input name: 'clientSecret',  title: 'Client Secret',  type: 'text', required: true
            input name: 'region',        title: 'Select your region', type: 'enum', options: countriesList, required: true
            input name: 'logLevel',      title: 'Log Level',      type: 'enum', options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
        }
        section('When ready, press Next to connect to Home Connect.') {}
    }
}

def pageAuthentication() {
    Utils.toLogger("debug", "Showing Authentication Page")

    if (!atomicState.accessToken) {
        atomicState.accessToken = createAccessToken()
    }

    return dynamicPage(
        name: 'pageAuthentication',
        title: 'Home Connect Authentication',
        nextPage: 'pageDevices',
        install: false,
        uninstall: false,
        refreshInterval: 0
    ) {
        section() {
            def title = "Connect to Home Connect"
            if (atomicState.oAuthAuthToken) {
                Utils.showHideNextButton(true)
                title = "Re-connect to Home Connect"
                paragraph '<b>Success!</b> You are connected to Home Connect. Please press Next.'
            } else {
                Utils.showHideNextButton(false)
                paragraph 'To continue, you need to connect Hubitat to Home Connect. Press the button below to connect.'
            }
            href url: generateOAuthUrl(), style: 'external', required: false,
                 title: title, description: "Tap to connect to Home Connect"
        }
    }
}

def pageDevices() {
    HomeConnectAPI.getHomeAppliances() { devices -> homeConnectDevices = devices }
    def deviceList = [:]
    state.foundDevices = []

    homeConnectDevices.each {
        deviceList << ["${it.haId}":"${it.name} (${it.type}) (${it.haId})"]
        state.foundDevices << [haId: it.haId, name: it.name, type: it.type]
    }

    return dynamicPage(
        name: 'pageDevices',
        title: 'Home Connect Devices (v3)',
        install: true,
        uninstall: true
    ) {
        section() {
            paragraph 'Select devices to create v3 child devices (with HC3- prefix).'
            input name: 'devices', title: 'Select Devices', type: 'enum', required: true,
                  multiple: true, options: deviceList
        }
    }
}

// ==== App behaviour ====

// v3: new child deviceNetworkId = "HC3-<haId>"
def homeConnectIdToDeviceNetworkId(haId) {
    return "HC3-${haId}"
}

def synchronizeDevices() {
    def childDevices = getChildDevices()
    def childrenMap  = childDevices.collectEntries { [ (it.deviceNetworkId): it ] }

    for (homeConnectDeviceId in settings.devices) {
        def hubitatDeviceId = homeConnectIdToDeviceNetworkId(homeConnectDeviceId)

        if (childrenMap.containsKey(hubitatDeviceId)) {
            childrenMap.remove(hubitatDeviceId)
            continue
        }

        def homeConnectDevice = state.foundDevices.find { it.haId == homeConnectDeviceId }
        if (!homeConnectDevice) continue

        def device
        switch (homeConnectDevice.type) {
            case "CleaningRobot":
                device = addChildDevice('craigde', 'Home Connect CleaningRobot v3', hubitatDeviceId)
            break
            case "CookProcessor":
                device = addChildDevice('craigde', 'Home Connect CookProcessor v3', hubitatDeviceId)
            break
            case "Dishwasher":
                device = addChildDevice('craigde', 'Home Connect Dishwasher v3', hubitatDeviceId)
            break
            case "Dryer":
                device = addChildDevice('craigde', 'Home Connect Dryer v3', hubitatDeviceId)
            break
            case "Washer":
                device = addChildDevice('craigde', 'Home Connect Washer v3', hubitatDeviceId)
            break
            case "WasherDryer":
                device = addChildDevice('craigde', 'Home Connect WasherDryer v3', hubitatDeviceId)
            break
            case "Freezer":
            case "FridgeFreezer":
            case "Refrigerator":
                device = addChildDevice('craigde', 'Home Connect FridgeFreezer v3', hubitatDeviceId)
            break
            case "CoffeeMaker":
                device = addChildDevice('craigde', 'Home Connect CoffeeMaker v3', hubitatDeviceId)
            break
            case "Hood":
                device = addChildDevice('craigde', 'Home Connect Hood v3', hubitatDeviceId)
            break
            case "Hob":
                device = addChildDevice('craigde', 'Home Connect Hob v3', hubitatDeviceId)
            break
            case "Oven":
                device = addChildDevice('craigde', 'Home Connect Oven v3', hubitatDeviceId)
            break
            case "WineCooler":
                device = addChildDevice('craigde', 'Home Connect WineCooler v3', hubitatDeviceId)
            break
            default:
                Utils.toLogger("error", "Not supported (v3): ${homeConnectDevice.type}")
            break
        }
    }

    deleteChildDevicesByDevices(childrenMap.values())
}

def deleteChildDevicesByDevices(devices) {
    for (d in devices) {
        deleteChildDevice(d.deviceNetworkId)
    }
}

// ===== v3: Parent-side API helpers (same signatures) =====

def intializeStatus(device, boolean checkActiveProgram = true) {
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")
    Utils.toLogger("info", "Initializing the status of the device ${haId}")

    HomeConnectAPI.getStatus(haId) { status ->
        device.deviceLog("info", "Status received: ${status}")
        // v3: device is responsible for parsing status via parseEvent if you want.
        // Here we simply hand raw lists to the device if it chooses to use them.
        device.parseStatus(status)
    }

    HomeConnectAPI.getSettings(haId) { settings ->
        device.deviceLog("info", "Settings received: ${settings}")
        device.parseSettings(settings)
    }

    if (checkActiveProgram) {
        try {
            HomeConnectAPI.getActiveProgram(haId) { activeProgram ->
                device.deviceLog("info", "ActiveProgram received: ${activeProgram}")
                device.parseActiveProgram(activeProgram)
            }
        } catch (Exception e) {
            // no active program
        }
    }
}

def startProgram(device, programKey, optionKey = "") {
    Utils.toLogger("debug", "startProgram device: ${device}")
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")

    HomeConnectAPI.setActiveProgram(haId, programKey, optionKey) { availablePrograms ->
        Utils.toLogger("info", "setActiveProgram availablePrograms: ${availablePrograms}")
    }
}

def stopProgram(device) {
    Utils.toLogger("debug", "stopProgram device: ${device}")
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")

    HomeConnectAPI.setStopProgram(haId) { availablePrograms ->
        Utils.toLogger("info", "stopProgram availablePrograms: ${availablePrograms}")
    }
}

def setPowerState(device, boolean state) {
    Utils.toLogger("debug", "setPowerState from ${device} - ${state}")
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")

    HomeConnectAPI.setSettings(
        haId,
        "BSH.Common.Setting.PowerState",
        state ? "BSH.Common.EnumType.PowerState.On" : "BSH.Common.EnumType.PowerState.Off"
    ) { settings ->
        device.deviceLog("info", "Settings Sent: ${settings}")
    }
}

// ... you can keep your other helpers (lighting, venting, etc.) exactly as in v1,
// just normalize haId via deviceNetworkId?.replaceFirst(/^HC3-/, "") the same way.

// ===== v3: Program & options helpers (unchanged, with haId normalization) =====

def getAvailableProgramList(device) {
    Utils.toLogger("debug", "getAvailableProgramList device: ${device}")
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")
    def availableProgramList = []

    HomeConnectAPI.getAvailablePrograms(haId) { availablePrograms ->
        Utils.toLogger("info", "getAvailableProgramList availablePrograms: ${availablePrograms}")
        availableProgramList = availablePrograms
    }

    return availableProgramList
}

def getAvailableProgramOptionsList(device, programKey) {
    Utils.toLogger("debug", "getAvailableProgramOptionsList device: ${device} - programKey: ${programKey}")
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")
    def availableProgramOptionsList = []

    HomeConnectAPI.getAvailableProgram(haId, "${programKey}") { availableProgram ->
        Utils.toLogger("debug", "getAvailableProgramOptionsList availableProgram: ${availableProgram}")
        if (availableProgram) {
            availableProgramOptionsList = availableProgram.options
        }
    }

    return availableProgramOptionsList
}

def setSelectedProgram(device, programKey, optionKey = "") {
    Utils.toLogger("debug", "setSelectedProgram device: ${device}")
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")

    HomeConnectAPI.setSelectedProgram(haId, programKey, optionKey) { availablePrograms ->
        Utils.toLogger("info", "setSelectedProgram availablePrograms: ${availablePrograms}")
    }
}

def setSelectedProgramOption(device, optionKey, optionValue) {
    Utils.toLogger("debug", "setSelectedProgramOption device: ${device} - optionKey: ${optionKey} - optionValue: ${optionValue}")
    def haId = device.deviceNetworkId?.replaceFirst(/^HC3-/, "")

    HomeConnectAPI.setSelectedProgramOption(haId, optionKey, optionValue) { availablePrograms ->
        Utils.toLogger("info", "setSelectedProgramOption availablePrograms: ${availablePrograms}")
    }
}

// ===== v3: Unified event entry point from drivers =====

/**
 * Driver (or a dedicated stream driver) calls this with a structured event:
 *   [ haId: "403060520614003484",
 *     key: "BSH.Common.Status.OperationState",
 *     value: "BSH.Common.EnumType.OperationState.Run",
 *     displayvalue: "Run" ]
 *
 * This app will route to the correct v3 child device and call:
 *   child.parseEvent(evt)
 */
def handleApplianceEvent(Map evt) {
    if (!evt?.haId || !evt?.key) return

    String childDni = "HC3-${evt.haId}"
    def child = getChildDevice(childDni)
    if (!child) {
        Utils.toLogger("warn", "No v3 child device found for haId ${evt.haId} (dni=${childDni})")
        return
    }

    if (child.metaClass.respondsTo(child, "parseEvent", Map)) {
        child.parseEvent(evt)
    } else {
        Utils.toLogger("warn", "Child ${child.displayName} does not implement parseEvent(Map evt)")
    }
}

// ===== Authentication (unchanged from your v1, just lifted) =====

// See Home Connect Developer documentation here: https://developer.home-connect.com/docs/authorization/flow

private final OAUTH_AUTHORIZATION_URL() { 'https://api.home-connect.com/security/oauth/authorize' }
private final OAUTH_TOKEN_URL()         { 'https://api.home-connect.com/security/oauth/token' }
private final ENDPOINT_APPLIANCES()     { '/api/homeappliances' }

mappings {
    path("/oauth/callback") { action: [GET: "oAuthCallback"] }
}

def generateOAuthUrl() {
    def timestamp  = now().toString()
    def stateValue = generateSecureState(timestamp)
    Utils.toLogger("debug", "Generated OAuth state with timestamp: ${timestamp}")

    def params = [
        'client_id'    : getClientId(),
        'redirect_uri' : getOAuthRedirectUrl(),
        'response_type': 'code',
        'scope'        : 'IdentifyAppliance Monitor Settings Control',
        'state'        : stateValue
    ]
    return "${OAUTH_AUTHORIZATION_URL()}?${Utils.toQueryString(params)}"
}

def generateHash(String message) {
    return message.hashCode().toString()
}

def generateSecureState(timestamp) {
    def message    = "${timestamp}:${getClientId()}:${getClientSecret()}"
    def hash       = generateHash(message)
    def stateValue = "${timestamp}:${hash}"
    return stateValue.bytes.encodeBase64().toString()
}

def validateSecureState(stateValue) {
    try {
        def decoded = new String(stateValue.decodeBase64())
        def parts   = decoded.split(':')

        if (parts.length != 2) {
            Utils.toLogger("error", "Invalid state format")
            return false
        }

        def timestamp    = parts[0]
        def receivedHash = parts[1]

        def stateAge = now() - timestamp.toLong()
        if (stateAge < 0 || stateAge > 600000) {
            Utils.toLogger("error", "State timestamp is too old or invalid: ${stateAge}ms")
            return false
        }

        def message      = "${timestamp}:${getClientId()}:${getClientSecret()}"
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
    return "${getFullApiServerUrl()}/oauth/callback?access_token=${atomicState.accessToken}"
}

def oAuthCallback() {
    Utils.toLogger("debug", "Received oAuth callback")
    Utils.toLogger("debug", "Callback params: ${params}")

    def code       = params.code
    def oAuthState = params.state

    if (!code) {
        Utils.toLogger("error", "No authorization code received in callback")
        return renderOAuthFailure()
    }

    if (!oAuthState) {
        Utils.toLogger("error", "No state received in callback")
        return renderOAuthFailure()
    }

    if (!validateSecureState(oAuthState)) {
        Utils.toLogger("error", "Invalid state received in callback: ${oAuthState}")
        return renderOAuthFailure()
    }

    Utils.toLogger("info", "State validation successful")

    atomicState.oAuthRefreshToken = null
    atomicState.oAuthAuthToken    = null
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
    Utils.toLogger("debug", "Acquiring OAuth Authentication Token")
    apiRequestAccessToken([
        'grant_type'  : 'authorization_code',
        'code'        : code,
        'client_id'   : getClientId(),
        'client_secret': getClientSecret(),
        'redirect_uri': getOAuthRedirectUrl()
    ])
}

def refreshOAuthToken() {
    Utils.toLogger("debug", "Refreshing OAuth Authentication Token")
    apiRequestAccessToken([
        'grant_type'   : 'refresh_token',
        'refresh_token': atomicState.oAuthRefreshToken,
        'client_secret': getClientSecret()
    ])
}

def apiRequestAccessToken(body) {
    try {
        httpPost(uri: OAUTH_TOKEN_URL(), requestContentType: 'application/x-www-form-urlencoded', body: body) { response ->
            if (response && response.data && response.success) {
                atomicState.oAuthRefreshToken = response.data.refresh_token
                atomicState.oAuthAuthToken    = response.data.access_token
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
    if (now() >= atomicState.oAuthTokenExpires - 60_000) {
        refreshOAuthToken()
    }
    return atomicState.oAuthAuthToken
}

def getLanguage() {
    return atomicState.langCode
}

def renderOAuthSuccess() {
    render contentType: 'text/html', data: '''
    <p>Your Home Connect Account is now connected to Hubitat (v3 app).</p>
    <p>Close this window to continue setup.</p>
    '''
}

def renderOAuthFailure() {
    render contentType: 'text/html', data: '''
    <p>Unable to connect to Home Connect. You can see the logs for more information.</p>
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

