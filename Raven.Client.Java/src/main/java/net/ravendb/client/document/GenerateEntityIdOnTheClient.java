package net.ravendb.client.document;

import java.lang.reflect.Field;
import java.util.UUID;

import net.ravendb.abstractions.basic.Reference;
import net.ravendb.abstractions.closure.Function1;
import net.ravendb.abstractions.data.Constants;
import net.ravendb.client.converters.ITypeConverter;

import org.apache.commons.lang.NullArgumentException;
import org.apache.commons.lang.reflect.FieldUtils;


public class GenerateEntityIdOnTheClient {
  private final DocumentConvention conventions;
  private final Function1<Object, String> generateKey;

  public GenerateEntityIdOnTheClient(DocumentConvention conventions, Function1<Object, String> generateKey) {
    this.conventions = conventions;
    this.generateKey = generateKey;
  }

  private Field getIdentityProperty(Class<?> entityType) {
    return conventions.getIdentityProperty(entityType);
  }

  /**
   * Attempts to get the document key from an instance
   * @param entity
   * @param idHolder
   */
  public boolean tryGetIdFromInstance(Object entity, Reference<String> idHolder) {
    if (entity == null) {
      throw new NullArgumentException("entity");
    }
    try {
      Field identityProperty = getIdentityProperty(entity.getClass());
      if (identityProperty != null) {
        Object value = FieldUtils.readField(identityProperty, entity, true);
        return getIdAsString(entity, value, identityProperty, idHolder);
      }
      idHolder.value = null;
      return false;
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("boxing")
  private boolean getIdAsString(Object entity, Object value, Field identityProperty, Reference<String> idHolder) {
    if (value instanceof String) {
      idHolder.value = (String) value;
    }
    if (idHolder.value == null && value == null && identityProperty.getType().equals(UUID.class)) {
      // fix for UUID as UUID is nullable type in Java
      value = Constants.EMPTY_UUID;
    }
    if (idHolder.value == null && value != null) { //need conversion
      idHolder.value = conventions.getFindFullDocumentKeyFromNonStringIdentifier().find(value, entity.getClass(), true);
      return true;
    }
    return idHolder.value != null;
  }

  /**
   * Tries to get the identity.
   * @param entity
   */
  public String getOrGenerateDocumentKey(Object entity) {
    Reference<String> idHolder = new Reference<>();
    tryGetIdFromInstance(entity, idHolder);
    String id = idHolder.value;
    if (id == null) {
      // Generate the key up front
      id = generateKey.apply(entity);
    }

    if (id != null && id.startsWith("/")) {
      throw new IllegalStateException("Cannot use value '" + id + "' as a document id because it begins with a '/'");
    }
    return id;
  }

  public String generateDocumentKeyForStorage(Object entity) {
    String id = getOrGenerateDocumentKey(entity);
    trySetIdentity(entity, id);
    return id;
  }

  /**
   * Tries to set the identity property
   */
  public void trySetIdentity(Object entity, String id) {
    Class<?> entityType = entity.getClass();
    Field identityProperty = conventions.getIdentityProperty(entityType);

    if (identityProperty == null) {
      return;
    }

    setPropertyOrField(identityProperty.getType(), entity, identityProperty, id);
  }

  private void setPropertyOrField(Class<?> propertyOrFieldType, Object entity, Field field, String id) {
    try {
      if (String.class.equals(propertyOrFieldType)) {
        FieldUtils.writeField(field, entity, id, true);
      } else { // need converting
        for (ITypeConverter converter : conventions.getIdentityTypeConvertors()) {
          if (converter.canConvertFrom(propertyOrFieldType)) {
            FieldUtils.writeField(field, entity, converter.convertTo(conventions.getFindIdValuePartForValueTypeConversion().find(entity, id)), true);
            return;
          }
        }
        throw new IllegalArgumentException("Could not convert identity to type " + propertyOrFieldType +
            " because there is not matching type converter registered in the conventions' IdentityTypeConvertors");
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }


}
