package uk.co.fe.models;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Class encapsulating a property, its ID and its bin collection data.
 */
public class PropertyData {
    
    @JsonValue
    private String propertyId;
    
    @JsonValue
    private List<BinCollectionData> binCollectionData;
    
    public PropertyData() {
    }
    
    public PropertyData(String propertyId, List<BinCollectionData> binCollectionData) {
        this.propertyId = propertyId;
        this.binCollectionData = binCollectionData;
    }
    
}