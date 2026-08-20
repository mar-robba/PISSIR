#!/bin/bash
FILE="ServeCentraleOperativa/src/main/java/it/uni/reti2/gateway/RestApiGateway.java"

# Add repository injection
sed -i 's/@Inject\n    TrafficLogicEngine statoRete;/@Inject\n    TrafficLogicEngine statoRete;\n\n    @Inject\n    it.uni.reti2.persistence.RailwayRepository repository;/g' "$FILE"

# Replace Panache calls
sed -i 's/Stazione\.findById(\([^)]*\)) != null/repository.esisteStazione(\1)/g' "$FILE"
sed -i 's/Stazione\.findById(/repository.trovaStazione(/g' "$FILE"
sed -i 's/\([a-zA-Z0-9_]*\)\.persist()/repository.salvaStazione(\1)/g' "$FILE" # wait this is risky
