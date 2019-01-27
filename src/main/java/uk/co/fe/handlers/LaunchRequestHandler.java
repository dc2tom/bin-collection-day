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
import com.amazonaws.services.s3.internal.Mimetypes;
import com.amazonaws.util.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

//TODO Version 2:
//  Store propertyId in DynamoDB. Try to look it up, if not in the DB then make the first call and store it.
//  Cuts down on repeated calls for the propertyId.
//  Further optimisation - Store next few dates from the second response. Look them up, if the dates are all in the past,
//  call again for fresh data.  Should make the skill much more responsive.
public class LaunchRequestHandler implements RequestHandler {

    private static final Logger LOGGER = LogManager.getLogger(LaunchRequestHandler.class);

    private static final String PERMISSIONS = "['read::alexa:device:all:address']";

    private static final Pattern PROPERTY_ID_PATTERN = Pattern.compile("data-uprn=\"(\\d+)");

    private static final Pattern BIN_COLLECTION_DAY_PATTERN = Pattern.compile("label for=\"(\\w+)");

    //TODO implement. first match day of week, second match date, third match bin type. Loops 20 times.
    private static final Pattern BIN_COLLECTION_DETAIL_PATTERN = Pattern.compile("label for=\"\\w*\">(.+?)<");

    private static final String BIN_COLLECTION_DAY_STRING = "Your %s bin is due on %s.";

    private static final String DB_PROPERTY_TABLE_NAME = "PropertyData";

    private static final String DB_PROPERTY_ID_VALUE = "propertyId";

    @Override
    public boolean canHandle(HandlerInput handlerInput) {
        return handlerInput.matches(Predicates.requestType(LaunchRequest.class));
    }

    @Override
    public Optional<Response> handle(HandlerInput handlerInput) {
        if (handlerInput.getRequestEnvelope().getContext().getSystem().getUser().getPermissions() != null &&
                handlerInput.getRequestEnvelope().getContext().getSystem().getUser().getPermissions().getConsentToken() != null) {

            final Address address = findDeviceAddress(handlerInput);
            LOGGER.info("Address is: " + address.toString());

            final String propertyId = obtainPropertyId(address);
            LOGGER.info("Property id is: " + propertyId);

            final String speechString = buildBinString(propertyId);

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

    String buildBinString(String propertyId) {
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();

        final HttpGet httpGet = new HttpGet("https://online.cheshireeast.gov.uk/MyCollectionDay/SearchByAjax/GetBartecJobList?uprn=" + propertyId);

        try {
            LOGGER.info("Calling cheshire east for bin collection days.");
            final HttpResponse response = httpClient.execute(httpGet);

            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                LOGGER.error("HTTP error: " + response.getStatusLine().getReasonPhrase());
            }

            final String responseBody = IOUtils.toString(response.getEntity().getContent());
            LOGGER.info("Got response: " + responseBody);

            String binCollectionDay = "not known";
            String binCollectionType = "not known";

            final Matcher binCollectionDayMatcher = BIN_COLLECTION_DAY_PATTERN.matcher(responseBody);
            if (binCollectionDayMatcher.find()) {
                binCollectionDay = binCollectionDayMatcher.group(1);
            }

            if (binCollectionDayMatcher.find()) {
                binCollectionType = binCollectionDayMatcher.group(1).replace("Empty_Standard_", "");
            }

            return format(BIN_COLLECTION_DAY_STRING, binCollectionType.equals("General_Waste") ? "Black" : "Silver", binCollectionDay);

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

    String obtainPropertyId(Address address) {
        String propertyId = getPropertyIdFromDatabase(address.getAddressLine1());
        if (propertyId == null) {
            propertyId = getPropertyIdFromWebservice(address);
            if (propertyId != null) {
                putPropertyIdInDatabase(address.getAddressLine1(), propertyId);
            } else {
                throwBinCollectionException();
            }
        }

        return propertyId;
    }

    private void throwBinCollectionException() {
        throw new IllegalArgumentException("Sorry, we were unable to find your bin collection details. " +
                "Please check the address assigned to your Alexa device is a valid address.");
    }

    private String getPropertyIdFromDatabase(String addressLine1) {
        final Map<String, AttributeValue> keyToGet = new HashMap<>();
        try {
            keyToGet.put("addressLine1", new AttributeValue(URLEncoder.encode(addressLine1, StandardCharsets.UTF_8.name())));
        } catch (UnsupportedEncodingException e) {
            LOGGER.error(e.getMessage(), e);
        }

        final GetItemRequest propertyIdRequest = new GetItemRequest()
                .withKey(keyToGet)
                .withTableName(DB_PROPERTY_TABLE_NAME);

        final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();

        try {
            final GetItemResult response = dynamoDB.getItem(propertyIdRequest);
            if (response != null && response.getItem() != null) {
                final AttributeValue propertyId = response.getItem().get(DB_PROPERTY_ID_VALUE);
                if (propertyId != null) {
                    LOGGER.info("Found propertyId in database: " + propertyId.getS());
                    return propertyId.getS();
                }
            }

            return null;
        } catch (AmazonServiceException e) {
            LOGGER.error(e.getMessage(), e);
        }

        return null;
    }

    private void putPropertyIdInDatabase(String addressLine1, String propertyId) {
        HashMap<String,AttributeValue> itemValues = new HashMap<>();

        try {
            itemValues.put("addressLine1", new AttributeValue(URLEncoder.encode(addressLine1, StandardCharsets.UTF_8.name())));
        } catch (UnsupportedEncodingException e) {
            LOGGER.error(e);
        }
        itemValues.put(DB_PROPERTY_ID_VALUE, new AttributeValue(propertyId));

        final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();

        try {
            dynamoDB.putItem(DB_PROPERTY_TABLE_NAME, itemValues);
        } catch (ResourceNotFoundException e) {
            LOGGER.error(format("Error: The table \"%s\" can't be found.\n", DB_PROPERTY_TABLE_NAME));
            throwBinCollectionException();
        } catch (AmazonServiceException e) {
            LOGGER.error(e.getMessage(), e);
            throwBinCollectionException();
        }
    }

    private String getPropertyIdFromWebservice(Address address) {
        final CloseableHttpClient httpClient = HttpClientBuilder.create().build();

        HttpGet httpGet = null;
        try {
            httpGet = new HttpGet("https://online.cheshireeast.gov.uk/MyCollectionDay/SearchByAjax/Search?postcode=" + URLEncoder.encode(address.getPostalCode(), "UTF-8") + "&propertyname=" + address.getAddressLine1().split(" ")[0]);
        } catch (UnsupportedEncodingException e) {
            LOGGER.error(e.getMessage(), e);
        }

        try {
            LOGGER.info("Calling cheshire east for address id.");
            final HttpResponse response = httpClient.execute(httpGet);

            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                LOGGER.error("HTTP error: " + response.getStatusLine().getReasonPhrase());
            }

            final String responseBody = IOUtils.toString(response.getEntity().getContent());
            LOGGER.info("Got response: " + responseBody);

            final Matcher propertyIdMatcher = PROPERTY_ID_PATTERN.matcher(responseBody);
            if (propertyIdMatcher.find()) {
                return propertyIdMatcher.group(1);
            }
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
}