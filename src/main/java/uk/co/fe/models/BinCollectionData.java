package uk.co.fe.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Pojo representing bin collection data returned from third-party API.
 */
public class BinCollectionData {

    @JsonProperty
    private String collectionDay;

    @JsonProperty
    private String collectionDate;

    @JsonProperty
    private String binType;

    public BinCollectionData(){
    }

    public BinCollectionData(String collectionDay, String collectionDate, String binType){
        this.collectionDay = collectionDay;
        this.collectionDate = collectionDate;
        this.binType = binType;
    }

}
