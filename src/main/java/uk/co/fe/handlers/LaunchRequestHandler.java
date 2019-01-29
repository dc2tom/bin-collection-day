package uk.co.fe.handlers;

import com.amazon.ask.dispatcher.request.handler.HandlerInput;
import com.amazon.ask.dispatcher.request.handler.RequestHandler;
import com.amazon.ask.model.LaunchRequest;
import com.amazon.ask.model.Response;
import com.amazon.ask.model.services.deviceAddress.Address;
import com.amazon.ask.model.services.deviceAddress.DeviceAddressServiceClient;
import com.amazon.ask.request.Predicates;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.util.IOUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.co.fe.models.BinCollectionData;
import uk.co.fe.models.PropertyData;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class LaunchRequestHandler implements RequestHandler {

    private static final Logger LOGGER = LogManager.getLogger(LaunchRequestHandler.class);

    private static final String PERMISSIONS = "['read::alexa:device:all:address']";

    private static final Pattern PROPERTY_ID_PATTERN = Pattern.compile("data-uprn=\"(\\d+)");

    private static final Pattern BIN_COLLECTION_DAY_PATTERN = Pattern.compile("label for=\"(\\w+)");

    //TODO implement. first match day of week, second match date, third match bin type. Loops 20 times.
    private static final Pattern BIN_COLLECTION_DETAIL_PATTERN = Pattern.compile("label for=\"\\w*\">(.+?)<");

    private static final String BIN_COLLECTION_DAY_STRING = "Your %s bin is due on %s.";

    private static final String DB_TABLE_NAME = "PropertyData";

    private static final String DB_PROPERTY_ID_COLUMN = "propertyId";

    private static final String DB_BIN_COLLECTION_DATA_COLUMN = "binCollectionData";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean canHandle(HandlerInput handlerInput) {
        return handlerInput.matches(Predicates.requestType(LaunchRequest.class));
    }

    @Override
    public Optional<Response> handle(HandlerInput handlerInput) {
        if (handlerInput.getRequestEnvelope().getContext().getSystem().getUser().getPermissions() != null &&
                handlerInput.getRequestEnvelope().getContext().getSystem().getUser().getPermissions().getConsentToken() != null) {

            final Address address = findDeviceAddress(handlerInput);
            LOGGER.info("Address obtained from device successfully.");

            final PropertyData propertyData = obtainPropertyData(address);
            //TODO error handling routines??

            final String speechString = buildBinString(propertyData);

            return handlerInput.getResponseBuilder()
                .withSpeech(speechString)
                .withShouldEndSession(true)
                .build();
        }

        return handlerInput.getResponseBuilder()
                .withSpeech("No Permissions found. If you want me to be able to tell you when your bins are due please grant this skill access to full address information in the Amazon Alexa App.")
                .withAskForPermissionsConsentCard(Collections.singletonList(PERMISSIONS))
                .build();
    }

    String buildBinString(PropertyData propertyData) {
//        CloseableHttpClient httpClient = HttpClientBuilder.create().build();
//
//        final HttpGet httpGet = new HttpGet("https://online.cheshireeast.gov.uk/MyCollectionDay/SearchByAjax/GetBartecJobList?uprn=" + propertyData);
//
//        try {
//            LOGGER.info("Calling cheshire east for bin collection days.");
//            final HttpResponse response = httpClient.execute(httpGet);
//
//            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
//                LOGGER.error("HTTP error: " + response.getStatusLine().getReasonPhrase());
//            }
//
//            final String responseBody = IOUtils.toString(response.getEntity().getContent());
//            LOGGER.info("Got response from cheshire east, parsing it.");

//            String binCollectionDay = "not known";
//            String binCollectionType = "not known";
//
//            final Matcher binCollectionDayMatcher = BIN_COLLECTION_DAY_PATTERN.matcher(responseBody);
//            if (binCollectionDayMatcher.find()) {
//                binCollectionDay = binCollectionDayMatcher.group(1);
//            }
//
//            if (binCollectionDayMatcher.find()) {
//                binCollectionType = binCollectionDayMatcher.group(1).replace("Empty_Standard_", "");
//            }

//            final String returnString = format(BIN_COLLECTION_DAY_STRING, binCollectionType.equals("General_Waste") ? "Black" : "Silver and Green", binCollectionDay);
//            LOGGER.info("Responding with:" + returnString);

            return null;
//        } catch (IOException e) {
//            LOGGER.error(e.getMessage(), e);
//        } finally {
//            try {
//                httpClient.close();
//            } catch (IOException e) {
//                LOGGER.error(e);
//            }
//        }
//
//        return null;
    }

    private Address findDeviceAddress(HandlerInput handlerInput) {
        final DeviceAddressServiceClient deviceAddressServiceClient = handlerInput.getServiceClientFactory().getDeviceAddressService();
        final String deviceId = handlerInput.getRequestEnvelope().getContext().getSystem().getDevice().getDeviceId();
        final Address address = deviceAddressServiceClient.getFullAddress(deviceId);

        if (address.getAddressLine1() == null || address.getPostalCode() == null) {
            LOGGER.error("Address is not complete. Line 1: " + address.getAddressLine1() + " Postcode: " + address.getPostalCode());
            throwBinCollectionException();
        }

        return address;
    }

    PropertyData obtainPropertyData(Address address) {
        try {
            final String urlEncodedAddressLine1 = URLEncoder.encode(address.getAddressLine1(), StandardCharsets.UTF_8.name());

            PropertyData propertyData = getPropertyDataFromDatabase(urlEncodedAddressLine1);
            //TODO property data needs to be refreshed and updated? -> call secondary lambda.

            if (propertyData == null) {
                propertyData = getPropertyDataFromWebservice(address);
                if (propertyData != null) {
                    putPropertyDataInDatabase(urlEncodedAddressLine1, propertyData);
                } else {
                    throwBinCollectionException();
                }
            }

            return propertyData;
        } catch (UnsupportedEncodingException e) {
            //TODO
            e.printStackTrace();
        }

        return null;
    }

    private void throwBinCollectionException() {
        throw new IllegalArgumentException("Sorry, we were unable to find your bin collection details. " +
                "Please check the address assigned to your Alexa device is a valid Cheshire East address.");
    }

    private PropertyData getPropertyDataFromDatabase(String addressLine1) {
        final Map<String, AttributeValue> keyToGet = new HashMap<>();
            keyToGet.put("addressLine1", new AttributeValue(addressLine1));

        final GetItemRequest propertyIdRequest = new GetItemRequest()
                .withKey(keyToGet)
                .withTableName(DB_TABLE_NAME);

        final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();

        try {
            final GetItemResult response = dynamoDB.getItem(propertyIdRequest);
            if (response != null && response.getItem() != null) {
                final AttributeValue propertyId = response.getItem().get(DB_PROPERTY_ID_COLUMN);
                if (propertyId != null) {
                    LOGGER.info("Found propertyId in database: " + propertyId.getS());
                    final AttributeValue binCollectionData = response.getItem().get(DB_BIN_COLLECTION_DATA_COLUMN);
                    if (binCollectionData != null) {
                        LOGGER.info("Found bin collection data in database:" + binCollectionData.getS());
                        final List<BinCollectionData> binCollectionDataList = Arrays.asList(objectMapper.readValue(binCollectionData.getS(), BinCollectionData[].class));

                        return new PropertyData(propertyId.getS(), binCollectionDataList);
                    }
                }
            }

            return null;
        } catch (AmazonServiceException | IOException e) {
            LOGGER.error(e.getMessage(), e);
        }

        return null;
    }

    private void putPropertyDataInDatabase(String addressLine1, PropertyData propertyData) {
        HashMap<String,AttributeValue> itemValues = new HashMap<>();

        itemValues.put("addressLine1", new AttributeValue(addressLine1));
        itemValues.put(DB_PROPERTY_ID_COLUMN, new AttributeValue(propertyData.getPropertyId()));

        try {
            itemValues.put(DB_BIN_COLLECTION_DATA_COLUMN, new AttributeValue(objectMapper.writeValueAsString(propertyData.getBinCollectionData())));
        } catch (JsonProcessingException e) {
            //TODO
            LOGGER.error(e.getMessage(), e);
        }

        final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();

        try {
            dynamoDB.putItem(DB_TABLE_NAME, itemValues);
        } catch (ResourceNotFoundException e) {
            LOGGER.error(format("Error: The table \"%s\" can't be found.\n", DB_TABLE_NAME));
            throwBinCollectionException();
        } catch (AmazonServiceException e) {
            LOGGER.error(e.getMessage(), e);
            throwBinCollectionException();
        }
    }

    private PropertyData getPropertyDataFromWebservice(Address address) {
        final CloseableHttpClient httpClient = HttpClientBuilder.create().build();

        try {
            final String propertyId = getPropertyIdFromWebservice(httpClient, address);
            final List<BinCollectionData> binCollectionData = getBinDataFromWebService(httpClient, propertyId);

            return new PropertyData(propertyId, binCollectionData);
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        } finally {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOGGER.error(e);
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
        }

        final String responseBody = IOUtils.toString(response.getEntity().getContent());
        LOGGER.info("Got propertyId response from cheshire east, parsing it");

        final Matcher propertyIdMatcher = PROPERTY_ID_PATTERN.matcher(responseBody);
        if (propertyIdMatcher.find()) {
            return propertyIdMatcher.group(1);
        } else {
            //TODO handle property ID not found error - is the property in cheshire east? is the address valid?
            return null;
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
        }

        return null;
    }

    List<BinCollectionData> parseBinResponse(String response) {
        final Matcher matcher = BIN_COLLECTION_DETAIL_PATTERN.matcher(response);

        final List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group(1));
        }

        final List<BinCollectionData> binCollectionData = new ArrayList<>();

        int i = 0;
        while (i < matches.size()) {
            binCollectionData.add(new BinCollectionData(matches.get(i++), matches.get(i++), matches.get(i++)));
        }

        return binCollectionData;
    }
}