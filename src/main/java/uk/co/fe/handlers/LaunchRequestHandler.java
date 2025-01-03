package uk.co.fe.handlers;

import static java.lang.String.format;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import com.amazon.ask.dispatcher.request.handler.HandlerInput;
import com.amazon.ask.dispatcher.request.handler.RequestHandler;
import com.amazon.ask.model.LaunchRequest;
import com.amazon.ask.model.Response;
import com.amazon.ask.model.services.ServiceException;
import com.amazon.ask.model.services.deviceAddress.Address;
import com.amazon.ask.model.services.deviceAddress.DeviceAddressServiceClient;
import com.amazon.ask.request.Predicates;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.util.IOUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.fe.models.BinCollectionData;
import uk.co.fe.models.PropertyData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LaunchRequestHandler implements RequestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LaunchRequestHandler.class);

    private static final String PERMISSIONS = "read::alexa:device:all:address";

    private static final Pattern PROPERTY_ID_PATTERN = Pattern.compile("data-uprn=\"(\\d+)");

    private static final Pattern BIN_COLLECTION_DETAIL_PATTERN = Pattern.compile("label for=\"\\w*\">(.+?)<");

    private static final String BIN_COLLECTION_DAY_STRING = "Your %s bin is due %s.";

    private static final String DB_TABLE_NAME = "PropertyData";

    private static final String DB_PROPERTY_ID_COLUMN = "propertyId";

    private static final String DB_BIN_COLLECTION_DATA_COLUMN = "binCollectionData";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter BIN_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final AmazonDynamoDB dynamoDB;

    private static final CloseableHttpClient httpClient;

    static {
        AmazonDynamoDBClientBuilder builder = AmazonDynamoDBClientBuilder.standard();
        builder.setRegion(Regions.EU_WEST_1.getName());
        dynamoDB = builder.build();
        httpClient = HttpClientBuilder.create().build();
    }

    @Override
    public boolean canHandle(HandlerInput handlerInput) {
        return handlerInput.matches(Predicates.requestType(LaunchRequest.class));
    }

    @Override
    public Optional<Response> handle(HandlerInput handlerInput) {
        if (handlerInput.getRequestEnvelope() != null && handlerInput.getRequestEnvelope().getContext() != null && handlerInput.getRequestEnvelope().getContext().getSystem() != null && handlerInput.getRequestEnvelope().getContext().getSystem().getUser() != null && handlerInput.getRequestEnvelope().getContext().getSystem().getUser().getPermissions() != null &&
                handlerInput.getRequestEnvelope().getContext().getSystem().getUser().getPermissions().getConsentToken() != null) {

            final Address address = findDeviceAddress(handlerInput);
            LOGGER.info("Address obtained from device successfully.");

            final PropertyData propertyData = obtainPropertyData(address);

            final String speechString = buildBinString(propertyData);

            return handlerInput.getResponseBuilder()
                    .withSpeech(speechString)
                    .withSimpleCard("Next Bin Collection", speechString)
                    .withShouldEndSession(true)
                    .build();
        }

        return handlerInput.getResponseBuilder()
                .withSpeech("No Address Permissions found. If you want me to be able to tell you when your bins are due please grant this skill access to full address information in the Amazon Alexa App.")
                .withAskForPermissionsConsentCard(Collections.singletonList(PERMISSIONS))
                .build();
    }

    private String buildBinString(PropertyData propertyData) {
        final List<BinCollectionData> binCollectionData = findNextBinCollectionData(propertyData);

        String binType;
        if (binCollectionData.size() == 2) {
            binType = binCollectionData.get(0).getBinType() + " and " + binCollectionData.get(1).getBinType();
        } else {
            binType = binCollectionData.get(0).getBinType();
        }

        final String returnString = format(BIN_COLLECTION_DAY_STRING, binType, obtainDateString(binCollectionData.get(0)));
        LOGGER.info("Responding with:{}", returnString);

        return returnString;
    }

    private String obtainDateString(BinCollectionData binCollectionData) {
        final LocalDate date = LocalDate.parse(binCollectionData.getCollectionDate(), BIN_DATE_FORMAT);

        if (LocalDate.now().isEqual(date)) {
            return "Today";
        }

        if (LocalDate.now().plusDays(1).isEqual(date)) {
            return "Tomorrow";
        }

        if (LocalDate.now().plusWeeks(1).isBefore(date)) {
            return "next " + binCollectionData.getCollectionDay();
        }

        return "on " + binCollectionData.getCollectionDay();
    }

    private Address findDeviceAddress(HandlerInput handlerInput) {
        final DeviceAddressServiceClient deviceAddressServiceClient = handlerInput.getServiceClientFactory().getDeviceAddressService();
        final String deviceId = handlerInput.getRequestEnvelope().getContext().getSystem().getDevice().getDeviceId();
        Address address;

        try {
            address = deviceAddressServiceClient.getFullAddress(deviceId);
        } catch (ServiceException e) {
            LOGGER.error(e.getMessage(), e);
            throw createBinCollectionException();
        }

        if (address == null) {
            throw createBinCollectionException();
        }

        if (address.getAddressLine1() == null || address.getPostalCode() == null) {
            LOGGER.error("Address is not complete. Line 1: " + address.getAddressLine1() + " Postcode: " + address.getPostalCode());
            throw createBinCollectionException();
        }

        return address;
    }

    PropertyData obtainPropertyData(Address address) {
        try {
            final String urlEncodedAddressLine1 = URLEncoder.encode(address.getAddressLine1(), StandardCharsets.UTF_8.name());

            PropertyData propertyData = getPropertyDataFromDatabase(urlEncodedAddressLine1);

            if (propertyData == null) {
                propertyData = getPropertyDataFromWebservice(address);
                if (propertyData != null) {
                    putPropertyDataInDatabase(urlEncodedAddressLine1, propertyData);
                } else {
                    throw createBinCollectionException();
                }
            }

            return propertyData;
        } catch (UnsupportedEncodingException e) {
            //should not be possible
        }

        throw createBinCollectionException();
    }

    private IllegalArgumentException createBinCollectionException() {
        return new IllegalArgumentException("Sorry, I was unable to find your bin collection details. Please check " +
                "the address assigned to your Alexa device is a valid Cheshire East address. I need the postal code and " +
                "first line to be filled in or I can't do my bin magic.");
    }

    private PropertyData getPropertyDataFromDatabase(String addressLine1) {
        final Map<String, AttributeValue> keyToGet = new HashMap<>();
        keyToGet.put("addressLine1", new AttributeValue(addressLine1));

        final GetItemRequest propertyDataRequest = new GetItemRequest()
                .withKey(keyToGet)
                .withTableName(DB_TABLE_NAME);

        try {
            final GetItemResult response = dynamoDB.getItem(propertyDataRequest);
            if (response != null && response.getItem() != null) {
                final AttributeValue propertyId = response.getItem().get(DB_PROPERTY_ID_COLUMN);
                if (propertyId != null) {
                    LOGGER.info("Found propertyId in database: " + propertyId.getS());
                    final AttributeValue binCollectionData = response.getItem().get(DB_BIN_COLLECTION_DATA_COLUMN);
                    if (binCollectionData != null) {
                        LOGGER.info("Found bin collection data in database.");
                        final List<BinCollectionData> binCollectionDataList = Arrays.asList(objectMapper.readValue(binCollectionData.getS(), BinCollectionData[].class));

                        return new PropertyData(addressLine1, propertyId.getS(), binCollectionDataList);
                    }
                }
            }

            LOGGER.info("No data found in database for this property.");
            return null;
        } catch (AmazonServiceException | IOException e) {
            LOGGER.error(e.getMessage(), e);
        }

        return null;
    }

    private void putPropertyDataInDatabase(String addressLine1, PropertyData propertyData) {
        HashMap<String, AttributeValue> itemValues = new HashMap<>();

        itemValues.put("addressLine1", new AttributeValue(addressLine1));
        itemValues.put(DB_PROPERTY_ID_COLUMN, new AttributeValue(propertyData.getPropertyId()));

        try {
            LOGGER.info("Writing to database.");
            itemValues.put(DB_BIN_COLLECTION_DATA_COLUMN, new AttributeValue(objectMapper.writeValueAsString(propertyData.getBinCollectionData())));
        } catch (JsonProcessingException e) {
            //TODO handle json exception
            LOGGER.error(e.getMessage(), e);
        }

        try {
            dynamoDB.putItem(DB_TABLE_NAME, itemValues);
        } catch (ResourceNotFoundException e) {
            LOGGER.error(format("Error: The table \"%s\" can't be found.\n", DB_TABLE_NAME));
            throw createBinCollectionException();
        } catch (AmazonServiceException e) {
            LOGGER.error(e.getMessage(), e);
            throw createBinCollectionException();
        }
    }

    private PropertyData getPropertyDataFromWebservice(Address address) {
        final CloseableHttpClient httpClient = HttpClientBuilder.create().build();

        try {
            final String propertyId = getPropertyIdFromWebservice(httpClient, address);
            final List<BinCollectionData> binCollectionData = getBinDataFromWebService(httpClient, propertyId);

            return new PropertyData(URLEncoder.encode(address.getAddressLine1(), StandardCharsets.UTF_8.name()), propertyId, binCollectionData);
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        } finally {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOGGER.error(String.valueOf(e));
            }
        }

        return null;
    }

    private String getPropertyIdFromWebservice(CloseableHttpClient httpClient, Address address) throws IOException {
        HttpGet httpGet = null;

        try {
            httpGet = new HttpGet("https://online.cheshireeast.gov.uk/MyCollectionDay/SearchByAjax/Search?postcode=" + URLEncoder.encode(address.getPostalCode(), "UTF-8") + "&propertyname=" + address.getAddressLine1().split(" ")[0]);
        } catch (UnsupportedEncodingException e) {
            LOGGER.error(e.getMessage(), e);
        }

        LOGGER.info("Calling cheshire east for property id.");
        final HttpResponse response = httpClient.execute(httpGet);

        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            LOGGER.error("HTTP error: " + response.getStatusLine().getReasonPhrase());
            throw createParsingException();
        }

        final String responseBody = IOUtils.toString(response.getEntity().getContent());
        LOGGER.info("Got propertyId response from cheshire east, parsing it");

        final Matcher propertyIdMatcher = PROPERTY_ID_PATTERN.matcher(responseBody);
        if (propertyIdMatcher.find()) {
            return propertyIdMatcher.group(1);
        } else {
            LOGGER.error("Unable to parse response from Cheshire east.");
            throw createBinCollectionException();
        }
    }

    List<BinCollectionData> getBinDataFromWebService(CloseableHttpClient httpClient, String propertyId) {
        final HttpGet httpGet = new HttpGet("https://online.cheshireeast.gov.uk/MyCollectionDay/SearchByAjax/GetBartecJobList?uprn=" + propertyId);

        try {
            LOGGER.info("Calling cheshire east for bin collection days.");
            final HttpResponse response = httpClient.execute(httpGet);

            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                LOGGER.error("HTTP error: " + response.getStatusLine().getReasonPhrase());
            }

            final String responseBody = IOUtils.toString(response.getEntity().getContent());
            LOGGER.info("Got bin data response from cheshire east, parsing it.");

            return parseBinResponse(responseBody);

        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
            throw createBinCollectionException();
        } finally {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    List<BinCollectionData> parseBinResponse(String response) {
        final Matcher matcher = BIN_COLLECTION_DETAIL_PATTERN.matcher(response);

        final List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group(1));
        }

        if (matches.isEmpty()){
            throw createParsingException();
        }

        final List<BinCollectionData> binCollectionData = new ArrayList<>();

        int i = 0;
        while (i < matches.size() && i < 30) {
            // We get 33 matches but need to stop creating collections at item 30. The final 3 matches are not valid data.
            // TODO improve the regex to omit the dodgy final 3 matches.
            binCollectionData.add(new BinCollectionData(matches.get(i++), matches.get(i++), parseBinType(matches.get(i++))));
        }

        return binCollectionData;
    }

    private RuntimeException createParsingException() {
        LOGGER.error("Unable to parse bin collection day response.");
        throw new IllegalArgumentException("Sorry, I was unable to obtain bin collection data for your property. " +
                "Please make sure you have set a valid Cheshire East address on your device so I can work my bin magic.");
    }

    private String parseBinType(String binTypeString) {
        if (binTypeString.contains("Empty Standard Mixed Recycling")) {
            return "Silver";
        }

        if (binTypeString.contains("Empty Bin Standard Garden Waste")) {
            return "Green";
        }

        // must be "Empty Standard General Waste"
        return "Black";
    }

    List<BinCollectionData> findNextBinCollectionData(PropertyData propertyData) {
        final LocalDate now = LocalDate.now();

        int counter = 1;
        boolean refreshed = false;
        List<BinCollectionData> nextCollectionData = new ArrayList<>();
        for (BinCollectionData item : propertyData.getBinCollectionData()) {
            final LocalDate date = LocalDate.parse(item.getCollectionDate(), BIN_DATE_FORMAT);
            if (date.isAfter(now) || date.isEqual(now)) {
                if ((propertyData.getBinCollectionData().size() - counter) <= 3 && !refreshed) {
                    //TODO invoke another lambda to refresh the data in the database... or make this call async
                    LOGGER.info("Running low on bin collection data.. needs a refresh.");
                    refreshBinData(propertyData);
                    refreshed = true;
                }
                if (nextCollectionData.size() == 1) {
                    // Does next bin in the collection belong with the one we are returning?
                    if (matchesExistingDate(nextCollectionData.get(0).getCollectionDate(), item.getCollectionDate())) {
                        LOGGER.info("Adding " + item.getBinType() + " bin to response.");
                        nextCollectionData.add(item);
                        break;
                    } else {
                        // We only have one bin to return
                        break;
                    }
                }
                if (nextCollectionData.size() == 0) {
                    // Black bins are only ever collected alone.
                    if ("Black".equals(item.getBinType())) {
                        LOGGER.info("Adding Black bin to response. No more bins to add.");
                        nextCollectionData.add(item);
                        break;
                    } else {
                        // Must be silver or green bin.
                        LOGGER.info("Adding " + item.getBinType() + " bin to response.");
                        nextCollectionData.add(item);
                    }
                }
            }

            if (counter == propertyData.getBinCollectionData().size() && nextCollectionData.size() == 0) {
                LOGGER.error("Bin collection data may be stale - forcing a refresh.");
                refreshBinData(propertyData);
                throw new IllegalArgumentException("Sorry, I was unable to find your Bin Collection day this time, but I have worked some bin magic. " +
                        "Please try again now that I have refreshed the data for your property.");
            }

            counter++;
        }

        if (nextCollectionData.size() == 0) {
            LOGGER.error("Couldn't find the next bin collection date for this property.");
            try {
                LOGGER.error("Data is: " + objectMapper.writeValueAsString(propertyData.getBinCollectionData()));
            } catch (JsonProcessingException e) {
                LOGGER.error("Property data doesn't appear to be valid: " + e.getMessage(), e);
            }
            throw createParsingException();
        }

        return nextCollectionData;
    }

    private boolean matchesExistingDate(String existingDate, String newDate) {
        return LocalDate.parse(existingDate, BIN_DATE_FORMAT).equals(LocalDate.parse(newDate, BIN_DATE_FORMAT));
    }

    private void refreshBinData(PropertyData propertyData) {
        LOGGER.info("Refreshing bin data for propertyId: " + propertyData.getPropertyId());

        final List<BinCollectionData> binCollectionData = getBinDataFromWebService(httpClient, propertyData.getPropertyId());
        putPropertyDataInDatabase(propertyData.getAddressLine1(), new PropertyData(propertyData.getAddressLine1(), propertyData.getPropertyId(), binCollectionData));
    }

}
