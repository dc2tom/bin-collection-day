package uk.co.fe.handlers;

import com.amazon.ask.model.services.deviceAddress.Address;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.Ignore;
import org.junit.Test;
import uk.co.fe.models.BinCollectionData;
import uk.co.fe.models.PropertyData;

import java.io.IOException;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Ignore //These are component tests.
public class LaunchRequestHandlerTest {

    private final LaunchRequestHandler testSubject = new LaunchRequestHandler();

    @Test
    public void shouldReturnPropertyId() {
        // Given
        final Address address = Address.builder()
                .withAddressLine1("youaddress")
                .withPostalCode("yourpostcode")
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
        final List<BinCollectionData> fullResponse = testSubject.getBinDataFromWebService(httpClient,"100010161153");



        // Then
        httpClient.close();
        //assertThat()

    }

    @Test
    public void shouldTest() throws JsonProcessingException {
        final List<BinCollectionData> result = testSubject.parseBinResponse(response);
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }

}