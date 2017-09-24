/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.materialFlowResources.service;

import static com.qcadoo.mes.materialFlowResources.constants.ResourceFields.QUANTITY;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.materialFlowResources.constants.DocumentFields;
import com.qcadoo.mes.materialFlowResources.constants.DocumentType;
import com.qcadoo.mes.materialFlowResources.constants.LocationFieldsMFR;
import com.qcadoo.mes.materialFlowResources.constants.MaterialFlowResourcesConstants;
import com.qcadoo.mes.materialFlowResources.constants.PositionFields;
import com.qcadoo.mes.materialFlowResources.constants.ReservationFields;
import com.qcadoo.mes.materialFlowResources.constants.ResourceFields;
import com.qcadoo.mes.materialFlowResources.constants.StorageLocationFields;
import com.qcadoo.mes.materialFlowResources.constants.WarehouseAlgorithm;
import com.qcadoo.mes.materialFlowResources.exceptions.InvalidResourceException;
import com.qcadoo.mes.materialFlowResources.helpers.NotEnoughResourcesErrorMessageCopyToEntityHelper;
import com.qcadoo.mes.materialFlowResources.helpers.NotEnoughResourcesErrorMessageHolder;
import com.qcadoo.mes.materialFlowResources.helpers.NotEnoughResourcesErrorMessageHolderFactory;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.DictionaryService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.NumberService;
import com.qcadoo.model.api.search.SearchCriteriaBuilder;
import com.qcadoo.model.api.search.SearchCriterion;
import com.qcadoo.model.api.search.SearchOrder;
import com.qcadoo.model.api.search.SearchOrders;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.model.api.validators.ErrorMessage;

@Service
public class ResourceManagementServiceImpl implements ResourceManagementService {

    private static final String _FIRST_NAME = "firstName";

    private static final String L_LAST_NAME = "lastName";

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private NumberService numberService;

    @Autowired
    private PalletNumberDisposalService palletNumberDisposalService;

    @Autowired
    private ResourceStockService resourceStockService;

    @Autowired
    private ReservationsService reservationsService;

    @Autowired
    private DictionaryService dictionaryService;

    @Autowired
    private NotEnoughResourcesErrorMessageHolderFactory notEnoughResourcesErrorMessageHolderFactory;

    public ResourceManagementServiceImpl() {

    }

    public ResourceManagementServiceImpl(final DataDefinitionService dataDefinitionService, final NumberService numberService) {
        this.dataDefinitionService = dataDefinitionService;
        this.numberService = numberService;
    }

    @Override
    @Transactional
    public void createResources(final Entity document) {
        DocumentType documentType = DocumentType.of(document);

        if (DocumentType.RECEIPT.equals(documentType) || DocumentType.INTERNAL_INBOUND.equals(documentType)) {
            createResourcesForReceiptDocuments(document);
        } else if (DocumentType.INTERNAL_OUTBOUND.equals(documentType) || DocumentType.RELEASE.equals(documentType)) {
            updateResourcesForReleaseDocuments(document);
        } else if (DocumentType.TRANSFER.equals(documentType)) {
            moveResourcesForTransferDocument(document);
        } else {
            throw new IllegalStateException("Unsupported document type");
        }
    }

    @Override
    @Transactional
    public void createResourcesForReceiptDocuments(final Entity document) {
        Entity warehouse = document.getBelongsToField(DocumentFields.LOCATION_TO);

        Object date = document.getField(DocumentFields.TIME);

        for (Entity position : document.getHasManyField(DocumentFields.POSITIONS)) {
            createResource(document, warehouse, position, date);

            position = position.getDataDefinition().save(position);

            if (!position.isValid()) {
                document.setNotValid();

                position.getGlobalErrors().forEach(e -> document.addGlobalError(e.getMessage(), e.getAutoClose(), e.getVars()));
                position.getErrors().values()
                        .forEach(e -> document.addGlobalError(e.getMessage(), e.getAutoClose(), e.getVars()));
            }
        }
    }

    private Entity createResource(final Entity document, final Entity warehouse, final Entity position, final Object date) {
        DataDefinition resourceDD = dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_RESOURCE);

        Entity product = position.getBelongsToField(PositionFields.PRODUCT);
        Entity resource = resourceDD.create();
        Entity user = document.getBelongsToField(DocumentFields.USER);
        Entity delivery = document.getBelongsToField(ResourceFields.DELIVERY);

        resource.setField(ResourceFields.USER_NAME, user.getStringField(_FIRST_NAME) + " " + user.getStringField(L_LAST_NAME));
        resource.setField(ResourceFields.TIME, date);
        resource.setField(ResourceFields.LOCATION, warehouse);
        resource.setField(ResourceFields.PRODUCT, position.getBelongsToField(PositionFields.PRODUCT));
        resource.setField(ResourceFields.QUANTITY, position.getField(PositionFields.QUANTITY));
        resource.setField(ResourceFields.RESERVED_QUANTITY, BigDecimal.ZERO);
        resource.setField(ResourceFields.AVAILABLE_QUANTITY, position.getDecimalField(PositionFields.QUANTITY));
        resource.setField(ResourceFields.PRICE, position.getField(PositionFields.PRICE));
        resource.setField(ResourceFields.BATCH, position.getField(PositionFields.BATCH));
        resource.setField(ResourceFields.EXPIRATION_DATE, position.getField(PositionFields.EXPIRATION_DATE));
        resource.setField(ResourceFields.PRODUCTION_DATE, position.getField(PositionFields.PRODUCTION_DATE));
        resource.setField(ResourceFields.STORAGE_LOCATION, position.getField(PositionFields.STORAGE_LOCATION));
        resource.setField(ResourceFields.ADDITIONAL_CODE, position.getField(PositionFields.ADDITIONAL_CODE));
        resource.setField(ResourceFields.PALLET_NUMBER, position.getField(PositionFields.PALLET_NUMBER));
        resource.setField(ResourceFields.TYPE_OF_PALLET, position.getField(PositionFields.TYPE_OF_PALLET));
        resource.setField(ResourceFields.WASTE, position.getField(PositionFields.WASTE));

        if (delivery != null) {
            resource.setField(ResourceFields.DELIVERY_NUMBER, delivery.getStringField("number"));
        }

        if (StringUtils.isEmpty(product.getStringField(ProductFields.ADDITIONAL_UNIT))) {
            resource.setField(ResourceFields.GIVEN_UNIT, product.getField(ProductFields.UNIT));
            resource.setField(ResourceFields.QUANTITY_IN_ADDITIONAL_UNIT, position.getField(PositionFields.QUANTITY));
            resource.setField(ResourceFields.CONVERSION, BigDecimal.ONE);
        } else {
            resource.setField(ResourceFields.GIVEN_UNIT, position.getField(PositionFields.GIVEN_UNIT));
            resource.setField(ResourceFields.QUANTITY_IN_ADDITIONAL_UNIT, position.getField(PositionFields.GIVEN_QUANTITY));
            resource.setField(ResourceFields.CONVERSION, position.getField(PositionFields.CONVERSION));
        }

        resourceStockService.createResourceStock(resource);

        resource = resourceDD.save(resource);

        if (!resource.isValid()) {
            throw new InvalidResourceException(resource);
        }

        position.setField("resourceReceiptDocument", resource.getId().toString());

        return resource;
    }

    private Entity createResource(final Entity position, final Entity warehouse, final Entity resource, final BigDecimal quantity,
            Object date) {
        DataDefinition resourceDD = dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_RESOURCE);

        Entity newResource = resourceDD.create();

        if (position != null) {
            Entity document = position.getBelongsToField(PositionFields.DOCUMENT);

            if (document != null) {
                Entity user = document.getBelongsToField(DocumentFields.USER);

                newResource.setField(ResourceFields.USER_NAME,
                        user.getStringField(_FIRST_NAME) + " " + user.getStringField(L_LAST_NAME));
            }
        }

        newResource.setField(ResourceFields.TIME, date);
        newResource.setField(ResourceFields.LOCATION, warehouse);
        newResource.setField(ResourceFields.PRODUCT, resource.getBelongsToField(PositionFields.PRODUCT));
        newResource.setField(ResourceFields.QUANTITY, quantity);
        newResource.setField(ResourceFields.AVAILABLE_QUANTITY, quantity);
        newResource.setField(ResourceFields.RESERVED_QUANTITY, BigDecimal.ZERO);
        newResource.setField(ResourceFields.PRICE, resource.getField(PositionFields.PRICE));
        newResource.setField(ResourceFields.BATCH, resource.getField(PositionFields.BATCH));
        newResource.setField(ResourceFields.EXPIRATION_DATE, resource.getField(PositionFields.EXPIRATION_DATE));
        newResource.setField(ResourceFields.PRODUCTION_DATE, resource.getField(PositionFields.PRODUCTION_DATE));
        newResource.setField(ResourceFields.STORAGE_LOCATION,
                findStorageLocationForProduct(warehouse, resource.getBelongsToField(ResourceFields.PRODUCT)));
        newResource.setField(ResourceFields.PALLET_NUMBER, null);
        newResource.setField(ResourceFields.TYPE_OF_PALLET, null);
        newResource.setField(ResourceFields.ADDITIONAL_CODE, resource.getField(ResourceFields.ADDITIONAL_CODE));
        newResource.setField(ResourceFields.CONVERSION, resource.getField(ResourceFields.CONVERSION));
        newResource.setField(ResourceFields.GIVEN_UNIT, resource.getField(ResourceFields.GIVEN_UNIT));

        BigDecimal quantityInAdditionalUnit = calculateAdditionalQuantity(quantity,
                resource.getDecimalField(ResourceFields.CONVERSION), resource.getStringField(ResourceFields.GIVEN_UNIT));

        newResource.setField(ResourceFields.QUANTITY_IN_ADDITIONAL_UNIT, quantityInAdditionalUnit);

        resourceStockService.createResourceStock(newResource);

        return resourceDD.save(newResource);
    }

    private BigDecimal calculateAdditionalQuantity(final BigDecimal quantity, final BigDecimal conversion, final String unit) {
        boolean isInteger = dictionaryService.checkIfUnitIsInteger(unit);

        if (isInteger) {
            return numberService.setScale(quantity.multiply(conversion), 0);
        }

        return numberService.setScale(quantity.multiply(conversion));
    }

    private Entity findStorageLocationForProduct(final Entity warehouse, final Entity product) {
        List<Entity> storageLocations = dataDefinitionService
                .get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER, MaterialFlowResourcesConstants.MODEL_STORAGE_LOCATION)
                .find().add(SearchRestrictions.belongsTo(StorageLocationFields.LOCATION, warehouse))
                .add(SearchRestrictions.belongsTo(StorageLocationFields.PRODUCT, product)).list().getEntities();

        if (storageLocations.isEmpty()) {
            return null;
        } else {
            return storageLocations.get(0);
        }
    }

    private SearchCriteriaBuilder getSearchCriteriaForResourceForProductAndWarehouse(final Entity product,
            final Entity warehouse) {
        return dataDefinitionService
                .get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER, MaterialFlowResourcesConstants.MODEL_RESOURCE).find()
                .add(SearchRestrictions.belongsTo(ResourceFields.LOCATION, warehouse))
                .add(SearchRestrictions.belongsTo(ResourceFields.PRODUCT, product))
                .add(SearchRestrictions.gt(ResourceFields.AVAILABLE_QUANTITY, BigDecimal.ZERO));

    }

    public Multimap<Long, BigDecimal> getQuantitiesInWarehouse(final Entity warehouse,
            final Multimap<Entity, Entity> productsAndPositions) {
        Multimap<Long, BigDecimal> result = ArrayListMultimap.create();

        String algorithm = warehouse.getStringField(LocationFieldsMFR.ALGORITHM);

        for (Map.Entry<Entity, Entity> productAndPosition : productsAndPositions.entries()) {
            Entity resource = productAndPosition.getValue().getBelongsToField(PositionFields.RESOURCE);

            if (algorithm.equalsIgnoreCase(WarehouseAlgorithm.MANUAL.getStringValue()) && resource != null) {
                result.put(productAndPosition.getKey().getId(), resource.getDecimalField(ResourceFields.AVAILABLE_QUANTITY));
            } else {
                Entity additionalCode = productAndPosition.getValue().getBelongsToField(PositionFields.ADDITIONAL_CODE);
                BigDecimal conversion = productAndPosition.getValue().getDecimalField(PositionFields.CONVERSION);

                Entity reservation = reservationsService.getReservationForPosition(productAndPosition.getValue());

                List<Entity> resources = Lists.newArrayList();

                if (additionalCode != null) {
                    SearchCriteriaBuilder scb = getSearchCriteriaForResourceForProductAndWarehouse(productAndPosition.getKey(),
                            warehouse);

                    if (!StringUtils.isEmpty(productAndPosition.getKey().getStringField(ProductFields.ADDITIONAL_UNIT))) {
                        scb.add(SearchRestrictions.eq(ResourceFields.CONVERSION, conversion));
                    } else {
                        scb.add(SearchRestrictions.eq(ResourceFields.CONVERSION, BigDecimal.ONE));
                    }

                    resources = scb.add(SearchRestrictions.belongsTo(ResourceFields.ADDITIONAL_CODE, additionalCode)).list()
                            .getEntities();

                    scb = getSearchCriteriaForResourceForProductAndWarehouse(productAndPosition.getKey(), warehouse);

                    if (!StringUtils.isEmpty(productAndPosition.getKey().getStringField(ProductFields.ADDITIONAL_UNIT))) {
                        scb.add(SearchRestrictions.eq(ResourceFields.CONVERSION, conversion));
                    } else {
                        scb.add(SearchRestrictions.eq(ResourceFields.CONVERSION, BigDecimal.ONE));
                    }

                    resources
                            .addAll(scb
                                    .add(SearchRestrictions.or(SearchRestrictions.isNull(ResourceFields.ADDITIONAL_CODE),
                                            SearchRestrictions.ne("additionalCode.id", additionalCode.getId())))
                                    .list().getEntities());
                }

                if (resources.isEmpty()) {
                    SearchCriteriaBuilder scb = getSearchCriteriaForResourceForProductAndWarehouse(productAndPosition.getKey(),
                            warehouse);

                    if (!StringUtils.isEmpty(productAndPosition.getKey().getStringField(ProductFields.ADDITIONAL_UNIT))) {
                        scb.add(SearchRestrictions.eq(ResourceFields.CONVERSION, conversion));
                    } else {
                        scb.add(SearchRestrictions.eq(ResourceFields.CONVERSION, BigDecimal.ONE));
                    }

                    resources = scb.list().getEntities();
                }

                BigDecimal reservedQuantity = BigDecimal.ZERO;

                if (reservation != null) {
                    reservedQuantity = reservation.getDecimalField(ReservationFields.QUANTITY);
                }

                if (result.containsKey(productAndPosition.getKey().getId())) {
                    BigDecimal currentQuantity = result.get(productAndPosition.getKey().getId()).stream().reduce(reservedQuantity,
                            BigDecimal::add);

                    result.put(productAndPosition.getKey().getId(),
                            (resources.stream().map(res -> res.getDecimalField(ResourceFields.AVAILABLE_QUANTITY))
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)).add(currentQuantity));
                } else {
                    result.put(productAndPosition.getKey().getId(),
                            resources.stream().map(res -> res.getDecimalField(ResourceFields.AVAILABLE_QUANTITY))
                                    .reduce(reservedQuantity, BigDecimal::add));
                }
            }
        }

        return result;
    }

    public BigDecimal getQuantityOfProductInWarehouse(final Entity warehouse, final Entity product, final Entity position) {
        BigDecimal quantity = BigDecimal.ZERO;

        Entity resource = position.getBelongsToField(PositionFields.RESOURCE);

        Entity reservation = reservationsService.getReservationForPosition(position);

        if (resource != null) {
            quantity = resource.getDecimalField(ResourceFields.AVAILABLE_QUANTITY);
        } else {
            List<Entity> resources = dataDefinitionService
                    .get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER, MaterialFlowResourcesConstants.MODEL_RESOURCE).find()
                    .add(SearchRestrictions.belongsTo(ResourceFields.LOCATION, warehouse))
                    .add(SearchRestrictions.belongsTo(ResourceFields.PRODUCT, product)).list().getEntities();

            for (Entity res : resources) {
                quantity = quantity.add(res.getDecimalField(ResourceFields.AVAILABLE_QUANTITY));
            }
        }

        if (reservation != null) {
            quantity = quantity.add(reservation.getDecimalField(ReservationFields.QUANTITY));
        }

        return quantity;
    }

    private Multimap<Entity, Entity> getProductsAndPositionsFromDocument(final Entity document) {
        Multimap<Entity, Entity> map = ArrayListMultimap.create();

        List<Entity> positions = document.getHasManyField(DocumentFields.POSITIONS);

        positions.forEach(position -> map.put(position.getBelongsToField(PositionFields.PRODUCT), position));

        return map;
    }

    private BigDecimal getQuantityOfProductFromMultimap(final Multimap<Long, BigDecimal> quantitiesForWarehouse,
            final Entity product) {
        List<BigDecimal> quantities = Lists.newArrayList(quantitiesForWarehouse.get(product.getId()));

        return quantities.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional
    public void updateResourcesForReleaseDocuments(final Entity document) {
        Entity warehouse = document.getBelongsToField(DocumentFields.LOCATION_FROM);

        WarehouseAlgorithm warehouseAlgorithm;

        boolean enoughResources = true;

        NotEnoughResourcesErrorMessageHolder errorMessageHolder = notEnoughResourcesErrorMessageHolderFactory.create();

        Multimap<Long, BigDecimal> quantitiesForWarehouse = ArrayListMultimap.create();

        for (Entity position : document.getHasManyField(DocumentFields.POSITIONS)) {
            Entity product = position.getBelongsToField(PositionFields.PRODUCT);
            Entity resource = position.getBelongsToField(PositionFields.RESOURCE);

            if (resource != null && resource.getId() != null) {
                resource = dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                        MaterialFlowResourcesConstants.MODEL_RESOURCE).get(resource.getId());
            }

            if (resource != null) {
                warehouse = resource.getBelongsToField(ResourceFields.LOCATION);
                warehouseAlgorithm = WarehouseAlgorithm.MANUAL;
            } else {
                warehouse = document.getBelongsToField(DocumentFields.LOCATION_FROM);
                warehouseAlgorithm = WarehouseAlgorithm.parseString(warehouse.getStringField(LocationFieldsMFR.ALGORITHM));
            }

            List<Entity> generatedPositions = updateResources(warehouse, position, warehouseAlgorithm);

            enoughResources = enoughResources && position.isValid();

            if (!position.isValid()) {
                if (quantitiesForWarehouse.isEmpty()) {
                    quantitiesForWarehouse = getQuantitiesInWarehouse(warehouse, getProductsAndPositionsFromDocument(document));
                }
                BigDecimal quantityInWarehouse;
                if (warehouseAlgorithm.equals(WarehouseAlgorithm.MANUAL)) {
                    quantityInWarehouse = getQuantityOfProductInWarehouse(warehouse, product, position);
                } else {
                    quantityInWarehouse = getQuantityOfProductFromMultimap(quantitiesForWarehouse, product);
                }

                BigDecimal quantity = position.getDecimalField(QUANTITY);

                errorMessageHolder.addErrorEntry(product, quantity.subtract(quantityInWarehouse, numberService.getMathContext()));
            } else {
                reservationsService.deleteReservationFromDocumentPosition(position);
                if (generatedPositions.size() > 1) {
                    if (Objects.nonNull(position.getId())) {
                        position.getDataDefinition().delete(position.getId());
                    }
                    for (Entity newPosition : generatedPositions) {
                        newPosition.setField(PositionFields.DOCUMENT, document);
                        Entity saved = newPosition.getDataDefinition().save(newPosition);
                        addPositionErrors(document, saved);
                    }
                } else {
                    copyPositionValues(position, generatedPositions.get(0));
                    Entity saved = position.getDataDefinition().save(position);
                    addPositionErrors(document, saved);
                }
            }
        }

        if (!enoughResources) {
            NotEnoughResourcesErrorMessageCopyToEntityHelper.addError(document, warehouse, errorMessageHolder);
        }
    }

    private void addPositionErrors(final Entity document, final Entity saved) {
        if (!saved.isValid()) {
            document.setNotValid();

            saved.getGlobalErrors().forEach(e -> document.addGlobalError(e.getMessage(), e.getAutoClose(), e.getVars()));

            if (!saved.getErrors().isEmpty()) {
                document.addGlobalError("materialFlow.document.fillResources.global.error.positionNotValid", false,
                        saved.getBelongsToField(PositionFields.PRODUCT).getStringField(ProductFields.NUMBER));
            }
        }
    }

    private void copyPositionValues(final Entity position, final Entity newPosition) {
        position.setField(PositionFields.PRICE, newPosition.getField(PositionFields.PRICE));
        position.setField(PositionFields.BATCH, newPosition.getField(PositionFields.BATCH));
        position.setField(PositionFields.PRODUCTION_DATE, newPosition.getField(PositionFields.PRODUCTION_DATE));
        position.setField(PositionFields.EXPIRATION_DATE, newPosition.getField(PositionFields.EXPIRATION_DATE));
        position.setField(PositionFields.RESOURCE, newPosition.getField(PositionFields.RESOURCE));
        position.setField(PositionFields.STORAGE_LOCATION, newPosition.getField(PositionFields.STORAGE_LOCATION));
        position.setField(PositionFields.ADDITIONAL_CODE, newPosition.getField(PositionFields.ADDITIONAL_CODE));
        position.setField(PositionFields.PALLET_NUMBER, newPosition.getField(PositionFields.PALLET_NUMBER));
        position.setField(PositionFields.TYPE_OF_PALLET, newPosition.getField(PositionFields.TYPE_OF_PALLET));
        position.setField(PositionFields.WASTE, newPosition.getField(PositionFields.WASTE));
        position.setField(PositionFields.QUANTITY, newPosition.getField(PositionFields.QUANTITY));
        position.setField(PositionFields.GIVEN_QUANTITY, newPosition.getField(PositionFields.GIVEN_QUANTITY));
    }

    private List<Entity> updateResources(final Entity warehouse, final Entity position, final WarehouseAlgorithm warehouseAlgorithm) {
        DataDefinition positionDD = dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_POSITION);

        List<Entity> newPositions = Lists.newArrayList();

        Entity product = position.getBelongsToField(PositionFields.PRODUCT);

        List<Entity> resources = getResourcesForWarehouseProductAndAlgorithm(warehouse, product, position, warehouseAlgorithm);

        BigDecimal quantity = position.getDecimalField(PositionFields.QUANTITY);

        for (Entity resource : resources) {
            BigDecimal resourceQuantity = resource.getDecimalField(ResourceFields.QUANTITY);
            BigDecimal resourceAvailableQuantity = resource.getDecimalField(ResourceFields.AVAILABLE_QUANTITY);

            Entity newPosition = positionDD.create();

            newPosition.setField(PositionFields.PRODUCT, position.getBelongsToField(PositionFields.PRODUCT));
            newPosition.setField(PositionFields.GIVEN_UNIT, position.getStringField(PositionFields.GIVEN_UNIT));
            newPosition.setField(PositionFields.WASTE, resource.getBooleanField(ResourceFields.WASTE));
            newPosition.setField(PositionFields.PRICE, resource.getField(ResourceFields.PRICE));
            newPosition.setField(PositionFields.BATCH, resource.getField(ResourceFields.BATCH));
            newPosition.setField(PositionFields.PRODUCTION_DATE, resource.getField(ResourceFields.PRODUCTION_DATE));
            newPosition.setField(PositionFields.EXPIRATION_DATE, resource.getField(ResourceFields.EXPIRATION_DATE));
            newPosition.setField(PositionFields.RESOURCE, resource);
            newPosition.setField(PositionFields.STORAGE_LOCATION, resource.getField(ResourceFields.STORAGE_LOCATION));
            newPosition.setField(PositionFields.ADDITIONAL_CODE, resource.getField(ResourceFields.ADDITIONAL_CODE));
            newPosition.setField(PositionFields.CONVERSION, position.getField(PositionFields.CONVERSION));
            newPosition.setField(PositionFields.PALLET_NUMBER, resource.getField(ResourceFields.PALLET_NUMBER));
            newPosition.setField(PositionFields.TYPE_OF_PALLET, resource.getField(ResourceFields.TYPE_OF_PALLET));

            if (quantity.compareTo(resourceAvailableQuantity) >= 0) {
                quantity = quantity.subtract(resourceAvailableQuantity, numberService.getMathContext());

                if (resourceQuantity.compareTo(resourceAvailableQuantity) <= 0) {
                    Entity palletNumberToDispose = resource.getBelongsToField(ResourceFields.PALLET_NUMBER);

                    resource.getDataDefinition().delete(resource.getId());

                    newPosition.setField(PositionFields.RESOURCE, null);

                    palletNumberDisposalService.tryToDispose(palletNumberToDispose);
                } else {
                    BigDecimal newResourceQuantity = resourceQuantity.subtract(resourceAvailableQuantity);
                    BigDecimal resourceConversion = resource.getDecimalField(ResourceFields.CONVERSION);
                    BigDecimal quantityInAdditionalUnit = calculateAdditionalQuantity(newResourceQuantity, resourceConversion,
                            resource.getStringField(ResourceFields.GIVEN_UNIT));

                    resource.setField(ResourceFields.AVAILABLE_QUANTITY, BigDecimal.ZERO);
                    resource.setField(ResourceFields.QUANTITY, newResourceQuantity);
                    resource.setField(ResourceFields.QUANTITY_IN_ADDITIONAL_UNIT,
                            numberService.setScale(quantityInAdditionalUnit));

                    Entity savedResource = resource.getDataDefinition().save(resource);

                    if (!savedResource.isValid()) {
                        throw new InvalidResourceException(savedResource);
                    }
                }

                newPosition.setField(PositionFields.QUANTITY, numberService.setScale(resourceAvailableQuantity));

                BigDecimal givenResourceQuantity = convertToGivenUnit(resourceAvailableQuantity, position);

                newPosition.setField(PositionFields.GIVEN_QUANTITY, numberService.setScale(givenResourceQuantity));

                newPositions.add(newPosition);

                if (BigDecimal.ZERO.compareTo(quantity) == 0) {
                    return newPositions;
                }
            } else {
                resourceQuantity = resourceQuantity.subtract(quantity, numberService.getMathContext());
                resourceAvailableQuantity = resourceAvailableQuantity.subtract(quantity, numberService.getMathContext());

                if (position.getBelongsToField(PositionFields.RESOURCE) != null
                        && reservationsService.reservationsEnabledForDocumentPositions()) {
                    BigDecimal reservedQuantity = resource.getDecimalField(ResourceFields.RESERVED_QUANTITY).subtract(quantity,
                            numberService.getMathContext());
                    resource.setField(ResourceFields.RESERVED_QUANTITY, reservedQuantity);
                }

                BigDecimal resourceConversion = resource.getDecimalField(ResourceFields.CONVERSION);
                BigDecimal quantityInAdditionalUnit = calculateAdditionalQuantity(resourceQuantity, resourceConversion,
                        resource.getStringField(ResourceFields.GIVEN_UNIT));

                resource.setField(ResourceFields.QUANTITY_IN_ADDITIONAL_UNIT, numberService.setScale(quantityInAdditionalUnit));
                resource.setField(ResourceFields.QUANTITY, numberService.setScale(resourceQuantity));
                resource.setField(ResourceFields.AVAILABLE_QUANTITY, resourceAvailableQuantity);

                Entity savedResource = resource.getDataDefinition().save(resource);

                if (!savedResource.isValid()) {
                    throw new InvalidResourceException(savedResource);
                }

                newPosition.setField(PositionFields.QUANTITY, numberService.setScale(quantity));

                BigDecimal givenQuantity = convertToGivenUnit(quantity, position);

                newPosition.setField(PositionFields.GIVEN_QUANTITY, numberService.setScale(givenQuantity));
                newPositions.add(newPosition);

                return newPositions;
            }
        }

        position.addError(position.getDataDefinition().getField(PositionFields.QUANTITY),
                "materialFlow.error.position.quantity.notEnough");

        return Lists.newArrayList(position);
    }

    public BigDecimal convertToGivenUnit(final BigDecimal quantity, final Entity position) {
        BigDecimal conversion = position.getDecimalField(PositionFields.CONVERSION);

        String givenUnit = position.getStringField(PositionFields.GIVEN_UNIT);

        return calculateAdditionalQuantity(quantity, conversion, givenUnit);
    }

    @Override
    @Transactional
    public void moveResourcesForTransferDocument(final Entity document) {
        Entity warehouseFrom = document.getBelongsToField(DocumentFields.LOCATION_FROM);
        Entity warehouseTo = document.getBelongsToField(DocumentFields.LOCATION_TO);

        Object date = document.getField(DocumentFields.TIME);

        WarehouseAlgorithm warehouseAlgorithm = WarehouseAlgorithm
                .parseString(warehouseFrom.getStringField(LocationFieldsMFR.ALGORITHM));

        boolean enoughResources = true;

        NotEnoughResourcesErrorMessageHolder errorMessageHolder = notEnoughResourcesErrorMessageHolderFactory.create();

        Multimap<Long, BigDecimal> quantitiesForWarehouse = ArrayListMultimap.create();

        for (Entity position : document.getHasManyField(DocumentFields.POSITIONS)) {
            Entity product = position.getBelongsToField(PositionFields.PRODUCT);

            moveResources(warehouseFrom, warehouseTo, position, date, warehouseAlgorithm);

            enoughResources = enoughResources && position.isValid();

            if (!position.isValid()) {
                if (quantitiesForWarehouse.isEmpty()) {
                    quantitiesForWarehouse = getQuantitiesInWarehouse(warehouseFrom,
                            getProductsAndPositionsFromDocument(document));
                }

                BigDecimal quantityInWarehouse;

                if (warehouseAlgorithm.equals(WarehouseAlgorithm.MANUAL)) {
                    quantityInWarehouse = getQuantityOfProductInWarehouse(warehouseFrom, product, position);
                } else {
                    quantityInWarehouse = getQuantityOfProductFromMultimap(quantitiesForWarehouse, product);
                }

                BigDecimal quantity = position.getDecimalField(QUANTITY);

                errorMessageHolder.addErrorEntry(product, quantity.subtract(quantityInWarehouse, numberService.getMathContext()));
            } else {
                reservationsService.deleteReservationFromDocumentPosition(position);
                position = position.getDataDefinition().save(position);
                if (!position.isValid()) {
                    document.setNotValid();

                    position.getGlobalErrors()
                            .forEach(e -> document.addGlobalError(e.getMessage(), e.getAutoClose(), e.getVars()));
                    position.getErrors().values()
                            .forEach(e -> document.addGlobalError(e.getMessage(), e.getAutoClose(), e.getVars()));
                }
            }
        }

        if (!enoughResources) {
            NotEnoughResourcesErrorMessageCopyToEntityHelper.addError(document, warehouseFrom, errorMessageHolder);
        }
    }

    private void moveResources(final Entity warehouseFrom, final Entity warehouseTo, final Entity position, final Object date,
            final WarehouseAlgorithm warehouseAlgorithm) {
        Entity product = position.getBelongsToField(PositionFields.PRODUCT);

        List<Entity> resources = getResourcesForWarehouseProductAndAlgorithm(warehouseFrom, product, position,
                warehouseAlgorithm);

        DataDefinition positionDD = dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_POSITION);

        BigDecimal quantity = position.getDecimalField(PositionFields.QUANTITY);

        for (Entity resource : resources) {
            BigDecimal resourceQuantity = resource.getDecimalField(QUANTITY);

            BigDecimal resourceAvailableQuantity = resource.getDecimalField(ResourceFields.AVAILABLE_QUANTITY);
            if (quantity.compareTo(resourceAvailableQuantity) >= 0) {
                quantity = quantity.subtract(resourceAvailableQuantity, numberService.getMathContext());

                if (resourceQuantity.compareTo(resourceAvailableQuantity) <= 0) {
                    Entity palletNumberToDispose = resource.getBelongsToField(ResourceFields.PALLET_NUMBER);

                    resource.getDataDefinition().delete(resource.getId());
                    position.setField(PositionFields.RESOURCE, null);

                    palletNumberDisposalService.tryToDispose(palletNumberToDispose);
                } else {
                    BigDecimal newResourceQuantity = resourceQuantity.subtract(resourceAvailableQuantity);
                    BigDecimal resourceConversion = resource.getDecimalField(ResourceFields.CONVERSION);

                    BigDecimal quantityInAdditionalUnit = calculateAdditionalQuantity(newResourceQuantity, resourceConversion,
                            resource.getStringField(ResourceFields.GIVEN_UNIT));

                    resource.setField(ResourceFields.AVAILABLE_QUANTITY, BigDecimal.ZERO);
                    resource.setField(ResourceFields.QUANTITY, newResourceQuantity);
                    resource.setField(ResourceFields.QUANTITY_IN_ADDITIONAL_UNIT,
                            numberService.setScale(quantityInAdditionalUnit));

                    resource.getDataDefinition().save(resource);
                }

                Entity newResource = createResource(position, warehouseTo, resource, resourceAvailableQuantity, date);

                if (BigDecimal.ZERO.compareTo(quantity) == 0) {
                    if (newResource.isValid()) {
                        return;
                    } else {
                        for (Map.Entry<String, ErrorMessage> error : newResource.getErrors().entrySet()) {
                            position.addError(positionDD.getField(error.getKey()), error.getValue().getMessage());
                        }
                    }
                }
            } else {
                resourceQuantity = resourceQuantity.subtract(quantity, numberService.getMathContext());
                resourceAvailableQuantity = resourceAvailableQuantity.subtract(quantity, numberService.getMathContext());

                String givenUnit = resource.getStringField(ResourceFields.GIVEN_UNIT);

                BigDecimal conversion = resource.getDecimalField(ResourceFields.CONVERSION);
                BigDecimal quantityInAdditionalUnit = calculateAdditionalQuantity(resourceQuantity, conversion, givenUnit);

                resource.setField(ResourceFields.QUANTITY_IN_ADDITIONAL_UNIT, numberService.setScale(quantityInAdditionalUnit));
                resource.setField(ResourceFields.QUANTITY, numberService.setScale(resourceQuantity));
                resource.setField(ResourceFields.AVAILABLE_QUANTITY, resourceAvailableQuantity);

                resource.getDataDefinition().save(resource);

                Entity newResource = createResource(position, warehouseTo, resource, quantity, date);

                if (newResource.isValid()) {
                    return;
                } else {

                    for (Map.Entry<String, ErrorMessage> error : newResource.getErrors().entrySet()) {
                        position.addError(positionDD.getField(error.getKey()), error.getValue().getMessage());
                    }
                }
            }
        }

        position.addError(position.getDataDefinition().getField(PositionFields.QUANTITY),
                "materialFlow.error.position.quantity.notEnough");
    }

    @Override
    public List<Entity> getResourcesForWarehouseProductAndAlgorithm(final Entity warehouse, final Entity product, final Entity position,
            final WarehouseAlgorithm warehouseAlgorithm) {
        List<Entity> resources = Lists.newArrayList();

        Entity resource = position.getBelongsToField(PositionFields.RESOURCE);

        if (resource != null && resource.getId() != null) {
            resource = dataDefinitionService
                    .get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER, MaterialFlowResourcesConstants.MODEL_RESOURCE)
                    .get(resource.getId());
        }

        Entity additionalCode = position.getBelongsToField(PositionFields.ADDITIONAL_CODE);

        if (resource != null) {
            Entity reservation = reservationsService.getReservationForPosition(position);

            if (reservation != null) {
                BigDecimal reservationQuantity = reservation.getDecimalField(ReservationFields.QUANTITY);
                BigDecimal resourceAvailableQuantity = resource.getDecimalField(ResourceFields.AVAILABLE_QUANTITY);

                resource.setField(ResourceFields.AVAILABLE_QUANTITY, resourceAvailableQuantity.add(reservationQuantity));
            }

            resources.add(resource);
        } else if (WarehouseAlgorithm.FIFO.equals(warehouseAlgorithm)) {
            resources = getResourcesForLocationAndProductFIFO(warehouse, product, additionalCode, position);
        } else if (WarehouseAlgorithm.LIFO.equals(warehouseAlgorithm)) {
            resources = getResourcesForLocationAndProductLIFO(warehouse, product, additionalCode, position);
        } else if (WarehouseAlgorithm.FEFO.equals(warehouseAlgorithm)) {
            resources = getResourcesForLocationAndProductFEFO(warehouse, product, additionalCode, position);
        } else if (WarehouseAlgorithm.LEFO.equals(warehouseAlgorithm)) {
            resources = getResourcesForLocationAndProductLEFO(warehouse, product, additionalCode, position);
        } else if (WarehouseAlgorithm.MANUAL.equals(warehouseAlgorithm)) {
            resources = getResourcesForLocationAndProductMANUAL(warehouse, product, additionalCode, position);
        }

        return resources;
    }

    private List<Entity> getResourcesForLocationAndProductMANUAL(final Entity warehouse, final Entity product,
            final Entity additionalCode, final Entity position) {
        Entity resource = position.getBelongsToField(PositionFields.RESOURCE);

        if (resource != null) {
            return Lists.newArrayList(resource);
        } else {
            return getResourcesForLocationAndProductFIFO(warehouse, product, additionalCode, position);
        }
    }

    private List<Entity> getResourcesForLocationCommonCode(final Entity warehouse, final Entity product,
            final Entity additionalCode, final Entity position, SearchOrder... searchOrders) {
        class SearchCriteriaHelper {

            private List<Entity> getAll() {
                return getAllThatSatisfies(null);
            }

            private List<Entity> getAllThatSatisfies(SearchCriterion searchCriterion) {
                SearchCriteriaBuilder scb = getSearchCriteriaForResourceForProductAndWarehouse(product, warehouse);

                if (!StringUtils.isEmpty(product.getStringField(ProductFields.ADDITIONAL_UNIT))) {
                    scb.add(SearchRestrictions.eq(PositionFields.CONVERSION,
                            position.getDecimalField(PositionFields.CONVERSION)));
                } else {
                    scb.add(SearchRestrictions.eq(ResourceFields.CONVERSION, BigDecimal.ONE));
                }

                Optional.ofNullable(searchCriterion).ifPresent(scb::add);

                for (SearchOrder searchOrder : searchOrders) {
                    scb.addOrder(searchOrder);
                }

                return scb.list().getEntities();
            }
        }

        List<Entity> resources = Lists.newArrayList();

        if (additionalCode != null) {
            resources = new SearchCriteriaHelper()
                    .getAllThatSatisfies(SearchRestrictions.belongsTo(ResourceFields.ADDITIONAL_CODE, additionalCode));

            resources.addAll(new SearchCriteriaHelper()
                    .getAllThatSatisfies(SearchRestrictions.or(SearchRestrictions.isNull(ResourceFields.ADDITIONAL_CODE),
                            SearchRestrictions.ne("additionalCode.id", additionalCode.getId()))));
        }

        if (resources.isEmpty()) {
            resources = new SearchCriteriaHelper().getAll();
        }

        return resources;
    }

    private List<Entity> getResourcesForLocationAndProductFIFO(final Entity warehouse, final Entity product,
            final Entity additionalCode, final Entity position) {
        return getResourcesForLocationCommonCode(warehouse, product, additionalCode, position,
                SearchOrders.asc(ResourceFields.TIME));
    }

    private List<Entity> getResourcesForLocationAndProductLIFO(final Entity warehouse, final Entity product,
            final Entity additionalCode, final Entity position) {
        return getResourcesForLocationCommonCode(warehouse, product, additionalCode, position,
                SearchOrders.desc(ResourceFields.TIME));
    }

    private List<Entity> getResourcesForLocationAndProductFEFO(final Entity warehouse, final Entity product,
            final Entity additionalCode, final Entity position) {
        return getResourcesForLocationCommonCode(warehouse, product, additionalCode, position,
                SearchOrders.asc(ResourceFields.EXPIRATION_DATE), SearchOrders.asc(ResourceFields.AVAILABLE_QUANTITY));
    }

    private List<Entity> getResourcesForLocationAndProductLEFO(final Entity warehouse, final Entity product,
            final Entity additionalCode, final Entity position) {
        return getResourcesForLocationCommonCode(warehouse, product, additionalCode, position,
                SearchOrders.desc(ResourceFields.EXPIRATION_DATE), SearchOrders.asc(ResourceFields.AVAILABLE_QUANTITY));
    }

}
