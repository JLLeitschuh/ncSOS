package com.asascience.ncsos.go;

import com.asascience.ncsos.cdmclasses.*;
import com.asascience.ncsos.outputformatter.ErrorFormatter;
import com.asascience.ncsos.outputformatter.go.Ioos10Formatter;
import com.asascience.ncsos.outputformatter.go.OosTethysFormatter;
import com.asascience.ncsos.service.BaseRequestHandler;
import com.asascience.ncsos.util.ListComprehension;
import com.asascience.ncsos.util.VocabDefinitions;

import ucar.ma2.Array;
import ucar.nc2.Attribute;
import ucar.nc2.Variable;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.constants.AxisType;
import ucar.nc2.constants.CF;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.units.DateUnit;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class GetObservationRequestHandler extends BaseRequestHandler {
    public static final String DEPTH = "depth";
    private static final String LAT = "latitude";
    private static final String LON = "longitude";

    public static final String TEXTXML = "text/xml";

    private String[] obsProperties;
    private String[] procedures;
    private iStationData CDMDataSet;
    private org.slf4j.Logger _log = org.slf4j.LoggerFactory.getLogger(GetObservationRequestHandler.class);
    public static final String FILL_VALUE_NAME = "_FillValue";
    public static final String IOOS10_RESPONSE_FORMAT = "text/xml;subtype=\"om/1.0.0/profiles/ioos_sos/1.0\"";
    public static final String OOSTETHYS_RESPONSE_FORMAT = "text/xml;subtype=\"om/1.0.0\"";
    private final List<String> eventTimes;
    private boolean requestFirstTime;
    private boolean requestLastTime;
    private static final String LATEST_TIME = "latest";
    private static final String FIRST_TIME = "first";
    /**
     * SOS get obs request handler
     * @param netCDFDataset dataset for which the get observation request is being made
     * @param requestedStationNames collection of offerings from the request
     * @param variableNames collection of observed properties from the request
     * @param eventTime event time range from the request
     * @param responseFormat response format from the request
     * @param latLonRequest map of the latitudes and longitude (points or ranges) from the request
     * @throws Exception 
     */
    public GetObservationRequestHandler(NetcdfDataset netCDFDataset,
                                        String[] requestedProcedures,
                                        String offering,
                                        String[] variableNames,
                                        String[] eventTime,
                                        String responseFormat,
                                        Map<String, String> latLonRequest) throws Exception {
        super(netCDFDataset);
        this.requestFirstTime = false;
        this.requestLastTime = false;
        eventTimes = setupGetObservation(netCDFDataset,
                                        requestedProcedures,
                                        offering,
                                        variableNames,
                                        eventTime,
                                        responseFormat,
                                        latLonRequest);
        
    }

    
    private List<String> setupGetObservation(NetcdfDataset netCDFDataset,
            String[] requestedProcedures,
            String offering,
            String[] variableNames,
            String[] eventTime,
            String responseFormat,
            Map<String, String> latLonRequest) throws Exception{
        List<String> localEventTime = new ArrayList<String>();
        // Translate back to an URN.  (gml:id fields in XML can't have colons)
        offering = offering.replace("_-_",":");
        offering = URLDecoder.decode(offering,"UTF-8");
        responseFormat = URLDecoder.decode(responseFormat,"UTF-8");

        //Remove any spaces between ";" and subtype
        responseFormat = responseFormat.replaceAll(";\\s+subtype",";subtype");
        boolean setProcedureFromOffering = false;
        // set up our formatter
        if (responseFormat.equalsIgnoreCase(OOSTETHYS_RESPONSE_FORMAT)) {
            formatter = new OosTethysFormatter(this);
        } else if (responseFormat.equalsIgnoreCase(IOOS10_RESPONSE_FORMAT)) {
            formatter = new Ioos10Formatter(this);
        } else {
            formatter = new ErrorFormatter();
            ((ErrorFormatter)formatter).setException("Could not recognize response format: " + responseFormat, 
                    INVALID_PARAMETER, "responseFormat");

            return localEventTime;

        }

        // Since the obsevedProperties can be standard_name attributes, map everything to an actual variable name here.

        String[] actualVariableNames = variableNames.clone();

        // make sure that all of the requested variable names are in the dataset
        for (int i = 0 ; i < variableNames.length ; i++) {
            String vars = variableNames[i];
            boolean isInDataset = false;
            for (Variable dVar : netCDFDataset.getVariables()) {
                String dVarFullName = dVar.getFullName();
                String obsUrl = this.getObservedOfferingUrl(dVarFullName);
                Attribute standardAtt = dVar.findAttribute((STANDARD_NAME));
                if (obsUrl != null && obsUrl.equalsIgnoreCase(vars) ) {
                    isInDataset = true;
                    // Replace standard_name with the variable name
                    actualVariableNames[i] = dVarFullName;
                    break;
                }
                else if(standardAtt != null && standardAtt.getStringValue().equals(vars)){
                	   isInDataset = true;
                       // Replace standard_name with the variable name
                       actualVariableNames[i] = dVarFullName;
                       break;
                }

            }
            if (!isInDataset) {
                formatter = new ErrorFormatter();
                ((ErrorFormatter)formatter).setException("observed property - " + vars + 
                        " - was not found in the dataset", INVALID_PARAMETER, "observedProperty");
                CDMDataSet = null;
                return localEventTime;
            }
        }

        CoordinateAxis heightAxis = netCDFDataset.findCoordinateAxis(AxisType.Height);

        this.obsProperties = checkNetcdfFileForAxis(heightAxis, actualVariableNames);

        // Figure out what procedures to use...
        try {
            if (requestedProcedures == null) {
                if (offering.equalsIgnoreCase(this.getUrnNetworkAll())) {
                    // All procedures
                    requestedProcedures = getStationNames().values().toArray(new String[getStationNames().values().size()]);
                } else {
                    // Just the single procedure supplied by the offering
                    requestedProcedures = new String[1];
                    requestedProcedures[0] = offering;
                }
                setProcedureFromOffering = true;
            } else {
                if (requestedProcedures.length == 1 && requestedProcedures[0].equalsIgnoreCase(getUrnNetworkAll())) {
                    requestedProcedures = getStationNames().values().toArray(new String[getStationNames().values().size()]);
                } else {
                    for (int i = 0; i < requestedProcedures.length; i++) {
                        requestedProcedures[i] = requestedProcedures[i].substring(requestedProcedures[i].lastIndexOf(":") + 1);
                    }
                }
            }
            // Now map them all to the station URN
            List<String> naProcs = ListComprehension.map(Arrays.asList(requestedProcedures),
                    new ListComprehension.Func<String, String>() {
                public String apply(String in) {
                    return getUrnName(in);
                }
            }
                    );
            this.procedures = naProcs.toArray(new String[naProcs.size()]);
        } catch (Exception ex) {
            _log.error(ex.toString());
            this.procedures = null;
        }

        // check that the procedures are valid
        boolean procedureError = checkProcedureValidity(setProcedureFromOffering);
        if(procedureError)
            return localEventTime;
        // and are a part of the offering
        if (offering != null) {
            checkProceduresAgainstOffering(offering);
        }

        if (eventTime != null && eventTime.length > 0) {
            Array timeVals = null;
            DateUnit dateUnit = null;
            for(int eventTimeI = 0; eventTimeI < eventTime.length; eventTimeI++){
                if(eventTime[eventTimeI].equals(LATEST_TIME) ||
                        eventTime[eventTimeI].equals(FIRST_TIME)){
                    int timeIndex = 0;
                    if(timeVals == null)
                        timeVals = this.timeVariable.read();
                    if(dateUnit == null)
                        dateUnit = new DateUnit(timeVariable.getUnitsString());
                    if(eventTime[eventTimeI].equals(FIRST_TIME)){
                        this.requestFirstTime = true;
                    }
                    
                    if(eventTime[eventTimeI].equals(LATEST_TIME)) {
                        timeIndex = (int)timeVals.getSize() - 1;
                        this.requestLastTime = true;
                    }
                    double lastT = timeVals.getDouble(timeIndex);
                    eventTime[eventTimeI] = dateUnit.makeStandardDateString(lastT);
                }

            }



            if(eventTime.length == 1){
                String currEntry = eventTime[0];
                eventTime = new String[2];
                eventTime[0] = currEntry;
                eventTime[1] = currEntry;
            }
            localEventTime = Arrays.asList(eventTime);

        } 
        setCDMDatasetForStations(netCDFDataset, eventTime, latLonRequest, heightAxis);

        return localEventTime;

    }
    private void setCDMDatasetForStations(NetcdfDataset netCDFDataset, String[] eventTime, 
            Map<String, String> latLonRequest,  CoordinateAxis heightAxis) throws IOException {
        // strip out text if the station is defined by indices
        /*
        if (isStationDefinedByIndices()) {
            String[] editedStationNames = new String[requestedStationNames.length];
            for (int i = 0; i < requestedStationNames.length; i++) {
                if (requestedStationNames[i].contains(UNKNOWN)) {
                    editedStationNames[i] = UNKNOWN;
                } else {
                    editedStationNames[i] = requestedStationNames[i].replaceAll("[A-Za-z]+", "");
                }
            }
            // copy array
            requestedStationNames = editedStationNames.clone();
        }
        */
        //grid operation
        if (getDatasetFeatureType() == FeatureType.GRID) {

            // Make sure latitude and longitude are specified
            if (!latLonRequest.containsKey(LON)) {
                formatter = new ErrorFormatter();
                ((ErrorFormatter)formatter).setException("No longitude point specified", MISSING_PARAMETER, "longitude");
                CDMDataSet = null;
                return;
            }
            if (!latLonRequest.containsKey(LAT)) {
                formatter = new ErrorFormatter();
                ((ErrorFormatter)formatter).setException("No latitude point specified", MISSING_PARAMETER, "latitude");
                CDMDataSet = null;
                return;
            }

            List<String> lats = Arrays.asList(latLonRequest.get(LAT).split(","));
            for (String s : lats) {
                try {
                    Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    formatter = new ErrorFormatter();
                    ((ErrorFormatter)formatter).setException("Invalid latitude specified", INVALID_PARAMETER, "latitude");
                    CDMDataSet = null;
                    return;
                }
            }
            List<String> lons = Arrays.asList(latLonRequest.get(LON).split(","));
            for (String s : lons) {
                try {
                    Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    formatter = new ErrorFormatter();
                    ((ErrorFormatter)formatter).setException("Invalid longitude specified", INVALID_PARAMETER, "longitude");
                    CDMDataSet = null;
                    return;
                }
            }


            Variable depthAxis;
            if (!latLonRequest.isEmpty()) {
                depthAxis = (netCDFDataset.findVariable(DEPTH));
                if (depthAxis != null) {
                    this.obsProperties = checkNetcdfFileForAxis((CoordinateAxis1D) depthAxis, this.obsProperties);
                }
                this.obsProperties = checkNetcdfFileForAxis(netCDFDataset.findCoordinateAxis(AxisType.Lat), this.obsProperties);
                this.obsProperties = checkNetcdfFileForAxis(netCDFDataset.findCoordinateAxis(AxisType.Lon), this.obsProperties);

                CDMDataSet = new Grid(this.procedures, eventTime, this.obsProperties, latLonRequest);
                CDMDataSet.setData(getGridDataset());
            }
        } //if the stations are not of cdm type grid then check to see and set cdm data type        
        else {
            FeatureType currType = getDatasetFeatureType();
            String stationsNamesFromUrn[] = new String[this.procedures.length];
            Map<String,String> urnMap =  this.getUrnToStationName();
            for(int statI = 0; statI < this.procedures.length; statI++){
            		stationsNamesFromUrn[statI]  = urnMap.get(procedures[statI]);
            }
            
            
            if (currType == FeatureType.TRAJECTORY) {
                CDMDataSet = new Trajectory(stationsNamesFromUrn, eventTime, this.obsProperties);
            } else if (currType  == FeatureType.STATION) {
                CDMDataSet = new TimeSeries(stationsNamesFromUrn, eventTime, this.obsProperties);
            } else if (currType  == FeatureType.STATION_PROFILE) {
                
                CDMDataSet = new TimeSeriesProfile(stationsNamesFromUrn, eventTime, 
                                                   this.obsProperties, this.requestFirstTime, this.requestLastTime,
                                                   this.timeVariable.getRank() > 1,
                                                   heightAxis);
            } else if (currType == FeatureType.PROFILE) {
                CDMDataSet = new Profile(stationsNamesFromUrn, eventTime, this.obsProperties);
            } else if (currType  == FeatureType.SECTION) {
                CDMDataSet = new Section(stationsNamesFromUrn, eventTime, this.obsProperties);
            } else {
                formatter = new ErrorFormatter();
                ((ErrorFormatter)formatter).setException("NetCDF-Java could not recognize the dataset's FeatureType");
                CDMDataSet = null;
                return;
            }
            
            //only set the data is it is valid
            CDMDataSet.setData(getFeatureTypeDataSet());
        }
    }

    /**
     * checks for the presence of height in the netcdf dataset if it finds it but not in the variables selected it adds it
     * @param Axis the axis being checked
     * @param variableNames1 the observed properties from the request (split)
     * @return updated observed properties (with altitude added, if found)
     */
    private String[] checkNetcdfFileForAxis(CoordinateAxis Axis, String[] variableNames1) {
        if (Axis != null) {
            List<String> variableNamesNew = new ArrayList<String>();
            //check to see if Z present
            boolean foundZ = false;
            for (int i = 0; i < variableNames1.length; i++) {
                String zAvail = variableNames1[i];

                if (zAvail.equalsIgnoreCase(Axis.getFullName())) {
                    foundZ = true;
                    break;
                }
            }

            //if it not found add it!
            if (!foundZ && !Axis.getDimensions().isEmpty()) {
                variableNamesNew = new ArrayList<String>();
                variableNamesNew.addAll(Arrays.asList(variableNames1));
                variableNamesNew.add(Axis.getFullName());
                variableNames1 = new String[variableNames1.length + 1];
                variableNames1 = (String[]) variableNamesNew.toArray(variableNames1);
                //*******************************
            }
        }
        return variableNames1;
    }

     /**
     * Create the observation data for go, passing it to our formatter
     */
    public void parseObservations() {
    	if(CDMDataSet != null){
    		for (int s = 0; s < CDMDataSet.getNumberOfStations(); s++) {
    			String dataString = CDMDataSet.getDataResponse(s);
    			for (String dataPoint : dataString.split(";")) {
    				if (!dataPoint.equals("")) {
    					formatter.addDataFormattedStringToInfoList(dataPoint);
    				}
    			}
    		}
    	}
    }



    public List<String> getRequestedEventTimes() {
        return this.eventTimes;
    }

    /**
     * Gets the dataset wrapped by the cdm feature type giving multiple easy to 
     * access functions
     * @return dataset wrapped by iStationData
     */
    public iStationData getCDMDataset() {
        return CDMDataSet;
    }

    //<editor-fold defaultstate="collapsed" desc="Helper functions for building GetObs XML">
    /**
     * Looks up a stations index by a string name
     * @param stName the name of the station to look for
     * @return the index of the station (-1 if it does not exist)
     */
    public int getIndexFromStationName(String stName) {
        return getStationIndex(stName);
    }

    public String getStationLowerCorner(int relIndex) {
        return formatDegree(CDMDataSet.getLowerLat(relIndex)) + " " + formatDegree(CDMDataSet.getLowerLon(relIndex));
    }

    public String getStationUpperCorner(int relIndex) {
        return formatDegree(CDMDataSet.getUpperLat(relIndex)) + " " + formatDegree(CDMDataSet.getUpperLon(relIndex));
    }

    public String getBoundedLowerCorner() {
        return formatDegree(CDMDataSet.getBoundLowerLat()) + " " + formatDegree(CDMDataSet.getBoundLowerLon());
    }

    public String getBoundedUpperCorner() {
        return formatDegree(CDMDataSet.getBoundUpperLat()) + " " + formatDegree(CDMDataSet.getBoundUpperLon());
    }

    public String getStartTime(int relIndex) {
        return CDMDataSet.getTimeBegin(relIndex);
    }

    public String getEndTime(int relIndex) {
        return CDMDataSet.getTimeEnd(relIndex);
    }

    public List<String> getRequestedObservedProperties() {
        CoordinateAxis heightAxis = netCDFDataset.findCoordinateAxis(AxisType.Height);

        List<String> retval = Arrays.asList(obsProperties);

        if (heightAxis != null) {
            retval = ListComprehension.filterOut(retval, heightAxis.getShortName());
        }

        return retval;
    }

    public String[] getObservedProperties() {
        return obsProperties;
    }

    public String[] getProcedures() {
        return procedures;
    }

    public String getUnitsString(String dataVarName) {
        return getUnitsOfVariable(dataVarName);
    }

    public String getValueBlockForAllObs(String block, String decimal, String token, int relIndex) {
        _log.info("Getting data for index: " + relIndex);
        String retval = CDMDataSet.getDataResponse(relIndex);
        return retval.replaceAll("\\.", decimal).replaceAll(",", token).replaceAll(";", block);
    }
    //</editor-fold>

    public String getFillValue(String obsProp) {
        Attribute[] attrs = getAttributesOfVariable(obsProp);
        for (Attribute attr : attrs) {
            if (attr.getFullNameEscaped().equalsIgnoreCase(FILL_VALUE_NAME)) {
                return attr.getValue(0).toString();
            }
        }
        return "";
    }

    public boolean hasFillValue(String obsProp) {
        Attribute[] attrs = getAttributesOfVariable(obsProp);
        if (attrs == null) {
            return false;
        }
        for (Attribute attr : attrs) {
            if (attr.getFullNameEscaped().equalsIgnoreCase(FILL_VALUE_NAME)) {
                return true;
            }
        }
        return false;

    }

    private boolean checkProcedureValidity(boolean procedureSetFromOffering) throws IOException {
        List<String> stProc = new ArrayList<String>();
        boolean errorFound = false;
        stProc.add(this.getUrnNetworkAll());
        for (String stname : this.getStationNames().values()) {
        	String stationUrn = this.getUrnName(stname);
            for (VariableSimpleIF senVar : this.getSensorNames().values()) {
                stProc.add(this.getSensorUrnName(stationUrn, senVar));
            }
            stProc.add(stationUrn);
        }

        for (String proc : this.procedures) {
            if (ListComprehension.filter(stProc, proc).size() < 1) {
                if(procedureSetFromOffering)
                    setOfferingException(proc);
                else 
                    setProcedureException(proc);
                errorFound = true;
            }
        }
        return errorFound;
    }

    private void setProcedureException(String proc){
        formatter = new ErrorFormatter();
        ((ErrorFormatter)formatter).setException("Invalid procedure " + proc + 
                ". Check GetCapabilities document for valid procedures.", INVALID_PARAMETER, "procedure");
    }
    
    private void setOfferingException(String offering){
        formatter = new ErrorFormatter();
        ((ErrorFormatter)formatter).setException("Offering: " + offering +
                " does not exist in the dataset.  Check GetCapabilities document for valid offerings.", 
                INVALID_PARAMETER, "offering");   
    }
    
    private void checkProceduresAgainstOffering(String offering) throws IOException {
        // if the offering is 'network-all' no error (network-all should have all procedures)
        if (offering.equalsIgnoreCase(this.getUrnNetworkAll())) {
            return;
        }
        // currently in ncSOS the only offerings that exist are network-all and each of the stations
        // in the dataset. So basically we need to check that the offering exists
        // in each of the procedures requested.
        for (String proc : this.procedures) {
            if (!proc.toLowerCase().contains(offering.toLowerCase())) {
                setOfferingException(offering);
            }
        }

    }
}
