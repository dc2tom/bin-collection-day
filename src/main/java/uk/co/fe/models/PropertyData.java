package uk.co.fe.models;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Class encapsulating a property, its first address line, ID and bin collection data.
 */
public class PropertyData {

    private String addressLine1;

    private String propertyId;
    
    private List<BinCollectionData> binCollectionData;
    
    public PropertyData(String addressLine1, String propertyId, List<BinCollectionData> binCollectionData) {
        this.addressLine1 = addressLine1;
        this.propertyId = propertyId;
        this.binCollectionData = binCollectionData;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public String getPropertyId() {
        return propertyId;
    }

    public List<BinCollectionData> getBinCollectionData() {
        return binCollectionData;
    }
    
}