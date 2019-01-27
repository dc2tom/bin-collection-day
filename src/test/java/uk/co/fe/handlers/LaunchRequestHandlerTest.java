package uk.co.fe.handlers;

import com.amazon.ask.model.services.deviceAddress.Address;
import org.junit.Ignore;
import org.junit.Test;

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
        final String propertyId = testSubject.obtainPropertyId(address);

        // Then
        assertThat(propertyId, is("100010161153"));
    }

    @Test
    public void shouldReturnFullResponseForPropertyId() {
        // When
        final String fullResponse = testSubject.buildBinString("100010161153");

        // Then //TODO assert properly, currently just using these tests to poke to cheshire east council API
        System.out.println(fullResponse);
    }

}