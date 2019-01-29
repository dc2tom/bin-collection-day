package uk.co.fe.models;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Class encapsulating a property, its ID and its bin collection data.
 */
public class PropertyData {

    private String propertyId;
    
    private List<BinCollectionData> binCollectionData;
    
    public PropertyData(String propertyId, List<BinCollectionData> binCollectionData) {
        this.propertyId = propertyId;
        this.binCollectionData = binCollectionData;
    }

    public String getPropertyId() {
        return propertyId;
    }

    public List<BinCollectionData> getBinCollectionData() {
        return binCollectionData;
    }
    
}