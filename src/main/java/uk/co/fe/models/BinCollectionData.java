package uk.co.fe.models;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Pojo representing bin collection data returned from third-party API.
 */
public class BinCollectionData {

    @JsonValue
    private String collectionDay;

    @JsonValue
    private String collectionDate;

    @JsonValue
    private String binType;

    public BinCollectionData(){
    }

    public BinCollectionData(String collectionDay, String collectionDate, String binType){
        this.collectionDay = collectionDay;
        this.collectionDate = collectionDate;
        this.binType = binType;
    }

}
