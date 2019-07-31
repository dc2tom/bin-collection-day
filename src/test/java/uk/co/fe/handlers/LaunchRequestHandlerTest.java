package uk.co.fe.handlers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.io.IOUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.Ignore;
import org.junit.Test;

import com.amazon.ask.model.services.deviceAddress.Address;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.fe.models.BinCollectionData;
import uk.co.fe.models.PropertyData;

@Ignore //These are component tests.
public class LaunchRequestHandlerTest {

    private final LaunchRequestHandler testSubject = new LaunchRequestHandler();

    @Test
    public void shouldReturnPropertyId() {
        // Given
        final Address address = Address.builder()
                .withAddressLine1("84")
                .withPostalCode("sk11 7yp")
                .build();

        // When
        final PropertyData propertyData = testSubject.obtainPropertyData(address);

        // Then
        assertThat(propertyData.getPropertyId(), is("121212121"));
    }

    @Test
    public void shouldReturnFullResponseForPropertyId() {
        // When
//        final String fullResponse = testSubject.buildBinString("100010161153");

        // Then
        // TODO assert properly, currently just using these 'tests' to poke to cheshire east council API
        //System.out.println(fullResponse);
    }

    private static String response = null;

    static {
        try {
            response = IOUtils.toString(LaunchRequestHandlerTest.class.getClassLoader().getResourceAsStream("data.txt"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void shouldConvertFullResponseIntoListOfBinCollectionData() throws IOException {
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();

        // When
        final List<BinCollectionData> fullResponse = testSubject.getBinDataFromWebService(httpClient,"100010085283");

        System.out.println(new ObjectMapper().writeValueAsString(fullResponse));

        // Then
        httpClient.close();
        //assertThat()

    }

    @Test
    public void shouldTest() throws JsonProcessingException {
        final List<BinCollectionData> result = testSubject.parseBinResponse(response);
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }

    @Test
    public void shouldConvertResponseIntoBinDays() throws Exception {
        // Given
        final String response  = IOUtils.toString(LaunchRequestHandlerTest.class.getClassLoader().getResourceAsStream("problem-data.txt"), Charset.forName("UTF-8"));
        PropertyData data = new PropertyData("1", "100010085283", new ObjectMapper().readValue(response, new TypeReference<List<BinCollectionData>>(){}));

        // When
        final List<BinCollectionData> result = testSubject.findNextBinCollectionData(data);

        // Then
        assertFalse(result.isEmpty());

    }

}