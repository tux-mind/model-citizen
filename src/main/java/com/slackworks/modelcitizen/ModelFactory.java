package com.slackworks.modelcitizen;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
   *
 * http://www.apache.org/licenses/LICENSE-2.0
   *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.builder.ReflectionToStringBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slackworks.modelcitizen.annotation.Blueprint;
import com.slackworks.modelcitizen.annotation.Default;
import com.slackworks.modelcitizen.annotation.Mapped;
import com.slackworks.modelcitizen.annotation.MappedList;
import com.slackworks.modelcitizen.annotation.MappedSet;
import com.slackworks.modelcitizen.annotation.NotSet;
import com.slackworks.modelcitizen.annotation.Nullable;
import com.slackworks.modelcitizen.erector.Command;
import com.slackworks.modelcitizen.field.DefaultField;
import com.slackworks.modelcitizen.field.FieldCallBack;
import com.slackworks.modelcitizen.field.MappedListField;
import com.slackworks.modelcitizen.field.MappedField;
import com.slackworks.modelcitizen.field.MappedSetField;
import com.slackworks.modelcitizen.field.ModelField;
import com.slackworks.modelcitizen.policy.BlueprintPolicy;
import com.slackworks.modelcitizen.policy.FieldPolicy;
import com.slackworks.modelcitizen.policy.Policy;
import com.slackworks.modelcitizen.policy.PolicyException;
import com.slackworks.modelcitizen.template.BlueprintTemplateException;
import com.slackworks.modelcitizen.template.JavaBeanTemplate;

/**
 * ModelFactory for generating Models. A Model's {@link Blueprint} is registered
 * with the ModelFactory. Then a Model can be generated with {@link #createModel(Class)}
 * or {@link #createModel(Object)}
 */
public class ModelFactory {

	private Logger logger = LoggerFactory.getLogger( this.getClass() );
	
	private List<Object> blueprints;
	private Map<Class,Erector> erectors = new HashMap<Class,Erector>();
	private Map<Class, List<FieldPolicy>> fieldPolicies = new HashMap<Class, List<FieldPolicy>>();
	private Map<Class, List<BlueprintPolicy>> blueprintPolicies = new HashMap<Class, List<BlueprintPolicy>>();
	
	/**
	 * Create new instance
	 */
	public ModelFactory() {
		blueprints = new ArrayList<Object>();
		erectors = new HashMap<Class,Erector>();
	}
	
	/**
	 * Add Policy to ModelFactory
	 *  
	 * @param policy {@link FieldPolicy} or {@link BlueprintPolicy}
	 * @throws PolicyException
	 */
	public void addPolicy( Policy policy ) throws PolicyException {
		
		// Add BlueprintPolicy
		if ( policy instanceof BlueprintPolicy ) {
			if ( erectors.get( policy.getTarget() ) == null ) {
				throw new PolicyException( "Blueprint does not exist for BlueprintPolicy target: " + policy.getTarget() );
			}
		
			List<BlueprintPolicy> policies = blueprintPolicies.get( policy.getTarget() );
			if ( policies == null ) {
				policies = new ArrayList<BlueprintPolicy>();
			}
			
			policies.add( (BlueprintPolicy)policy );
			
			logger.info( "Setting BlueprintPolicy {} for {}", policy, policy.getTarget() );
			
			blueprintPolicies.put( policy.getTarget(), policies );
		
		// Add FieldPolicy
		} else if ( policy instanceof FieldPolicy ) {
			
			// XXX: force FieldPolicy's to be mapped to a blueprint? Limits their scope, but enables validation
			if ( erectors.get( policy.getTarget() ) == null ) {
				throw new PolicyException( "Blueprint does not exist for FieldPolicy target: " + policy.getTarget() );
			}
			
			List<FieldPolicy> policies = fieldPolicies.get( policy.getTarget() );
			if ( policies == null ) {
				policies = new ArrayList<FieldPolicy>();
			}
			
			policies.add( (FieldPolicy)policy );
			
			logger.info( "Setting FieldPolicy {} for {}", policy, policy.getTarget() );
			
			fieldPolicies.put( policy.getTarget(), policies );
		}
	}
	
	/**
	 * Register a List of {@link Blueprint}, Class<Blueprint>, or String
	 * class names of Blueprint
	 * 
	 * @param blueprints List
	 * @throws RegisterBlueprintException
	 */
	public void setRegisterBlueprints( List blueprints ) throws RegisterBlueprintException {
		for( Object blueprint : blueprints ) {
			if ( blueprint instanceof Class ) {
				registerBlueprint( (Class)blueprint );
			} else if ( blueprint instanceof String ) {
				registerBlueprint( (String)blueprint );
			} else if ( blueprint instanceof String ) {
				registerBlueprint( blueprint );
			} else {
				throw new RegisterBlueprintException( "Only supports List comprised of Class<Blueprint>, Blueprint, or String className" );
			}
		}
	}
	
	/**
	 * Register a {@link Blueprint} from a String Class name
	 */
	public void registerBlueprint( String className ) throws RegisterBlueprintException {
		try {
			registerBlueprint( Class.forName( className ) );
		} catch (ClassNotFoundException e) {
			throw new RegisterBlueprintException(e);
		}
	}
	
	/**
	 * Register a {@link Blueprint} from Class
	 * 
	 * @param clazz Blueprint class
	 * @throws RegisterBlueprintException
	 */
	public void registerBlueprint( Class clazz ) throws RegisterBlueprintException {
		Object blueprint = null;
		
		try {
			blueprint = clazz.newInstance();
		} catch (InstantiationException e) {
			throw new RegisterBlueprintException(e);
		} catch (IllegalAccessException e) {
			throw new RegisterBlueprintException(e);
		}
		
		registerBlueprint( blueprint );
	}
	
	/**
	 * Register {@link Blueprint} from instance.
	 * 
	 * @param blueprint {@link Blueprint}
	 * @throws RegisterBlueprintException
	 */
	public void registerBlueprint( Object blueprint ) throws RegisterBlueprintException {
		
		Blueprint blueprintAnnotation = blueprint.getClass().getAnnotation( Blueprint.class );
		if ( blueprintAnnotation == null ) {
			throw new RegisterBlueprintException( "Blueprint class not annotated by @Blueprint: " + blueprint );
		}
		Class target = blueprintAnnotation.value();
		
		List<ModelField> modelFields = new ArrayList<ModelField>();
		
		logger.info( "Registering blueprint for {}", target );
		
		// Iterate Blueprint public fields for ModelCitizen annotations
		Field[] fields = blueprint.getClass().getFields();
		for( Field field: fields ) {
			
			// Process @Default
			if ( field.getAnnotation( Default.class ) != null ) {
				
				DefaultField defaultField = new DefaultField();
				defaultField.setName( field.getName() );
				
				try {
					defaultField.setValue( field.get( blueprint ) );
				} catch (IllegalArgumentException e) {
					throw new RegisterBlueprintException( e );
				} catch (IllegalAccessException e) {
					throw new RegisterBlueprintException( e );
				}
				
				defaultField.setTarget( field.getType() );
				defaultField.setFieldClass( field.getType() );
				modelFields.add( defaultField );
				
				logger.debug( "  Setting default for {} to {}", defaultField.getName(), defaultField.getValue() );
				
			}
			
			// Process @Mapped
			Mapped mapped = field.getAnnotation( Mapped.class ); 
			if ( mapped != null ) {
				MappedField mappedField = new MappedField();
				mappedField.setName( field.getName() );
				
				if ( field.getAnnotation( Nullable.class ) != null ) {
					mappedField.setNullable( true );
				}
				
				// If @Mapped(target) not set, use Field's class
				if ( NotSet.class.equals( mapped.target() ) ) {
					mappedField.setTarget( field.getType() );
					
				// Use @Mapped(target) for MappedField#target
				} else {
					mappedField.setTarget( mapped.target() );
				}
					
				mappedField.setFieldClass( field.getType() );
				modelFields.add( mappedField );
				
				logger.debug( "  Setting mapped for {} to {}", mappedField.getName(), mappedField.getTarget());
			}
			
			// Process @MappedList
			MappedList mappedCollection = field.getAnnotation( MappedList.class );
			if ( mappedCollection != null ) {
				MappedListField listField = new MappedListField();
				listField.setName( field.getName() );
				listField.setFieldClass( field.getType() );
				listField.setSize( mappedCollection.size() );
				
				// If @MappedList(target) not set, use Field's class
				if ( NotSet.class.equals( mappedCollection.target() ) ) {
					listField.setTarget( field.getType() );
					
				// Use @MappedList(target) for MappedListField#target
				} else {
					listField.setTarget( mappedCollection.target() );
				}
				
				// If @MappedList(targetList) not set, use ArrayList
				if ( NotSet.class.equals( mappedCollection.targetList() ) ) {
					listField.setTargetList( ArrayList.class );
				} else {
					
					// Ensure that the targetList implements List
					boolean implementsList = false;
					for( Class interf : mappedCollection.targetList().getInterfaces() ) {
						if ( List.class.equals( interf ) ) {
							implementsList = true;
							break;
						}
					}
					
					if ( !implementsList ) {
						throw new RegisterBlueprintException( "@MappedList targetList must implement List for field " + field.getName() );
					}
					
					listField.setTargetList( mappedCollection.targetList() );
				}
				
				modelFields.add( listField );
				
				logger.debug( "  Setting mapped list for {} to {} as <{}>", new Object[] { listField.getName(), listField.getFieldClass(), listField.getTarget() });
				
			}
			
			// Process @MappedSet
			MappedSet mappedSet = field.getAnnotation( MappedSet.class );
			if ( mappedSet != null ) {
				MappedSetField setField = new MappedSetField();
				setField.setName( field.getName() );
				setField.setFieldClass( field.getType() );
				setField.setSize( mappedSet.size() );
				
				// If @MappedSet(target) not set, use Field's class
				if ( NotSet.class.equals( mappedSet.target() ) ) {
					setField.setTarget( field.getType() );
					
				// Use @MappedSet(target) for MappedSet#target
				} else {
					setField.setTarget( mappedSet.target() );
				}
				
				// If @MappedList(targetList) not set, use HashSet
				if ( NotSet.class.equals( mappedSet.targetSet() ) ) {
					setField.setTargetSet( HashSet.class );
				} else {
					
					// Ensure that the targetSet implements Set
					boolean implementsSet = false;
					for( Class interf : mappedSet.targetSet().getInterfaces() ) {
						if ( Set.class.equals( interf ) ) {
							implementsSet = true;
							break;
						}
					}
					
					if ( !implementsSet ) {
						throw new RegisterBlueprintException( "@MappedSet targetSet must implement Set for field " + field.getName() );
					}
					
					setField.setTargetSet( mappedSet.targetSet() );
				}
				
				modelFields.add( setField );
				
				logger.debug( "  Setting mapped set for {} to {} as <{}>", new Object[] { setField.getName(), setField.getFieldClass(), setField.getTarget() });
				
			}
		}
		
		blueprints.add( blueprint );
		
		// Create Erector for this Blueprint
		Erector erector = new Erector();
		erector.setTemplate( new JavaBeanTemplate() );
		erector.setBlueprint( blueprint );
		erector.setModelFields( modelFields );
		erector.setTarget( target );
		
		erectors.put( target, erector );
	}
	
	/**
	 * Create a Model for a registered {@link Blueprint}
	 * 
	 * @param clazz Model class
	 * @return Model
	 * @throws CreateModelException
	 */
	public <T> T createModel( Class<T> clazz ) throws CreateModelException {
		try {
			return createModel( clazz.newInstance() );
		} catch (InstantiationException e) {
			throw new CreateModelException( e );
		} catch (IllegalAccessException e) {
			throw new CreateModelException( e );
		}
	}

	/**
	 * Create a Model for a registered {@link Blueprint}
	 * 
	 * @param clazz Model class
	 * @return Model
	 * @throws CreateModelException
	 */
	public <T> T createModel( Class<T> clazz, boolean withPolicies) throws CreateModelException {
		try {
			return createModel( clazz.newInstance(), withPolicies );
		} catch (InstantiationException e) {
			throw new CreateModelException( e );
		} catch (IllegalAccessException e) {
			throw new CreateModelException( e );
		}
	}
	
	/**
	 * Create a Model for a registered {@link Blueprint}. Values set in the
	 * model will not be overridden by defaults in the {@link Blueprint}.
	 * 
	 * @param model Object
	 * @return Model
	 * @throws CreateModelException
	 */
	public <T> T createModel( T referenceModel ) throws CreateModelException {
		return createModel( referenceModel, true );
	}
	
	/**
	 * Create a Model for a registered {@link Blueprint}. Values set in the
	 * model will not be overridden by defaults in the {@link Blueprint}.
	 * 
	 * @param model Object
	 * @return Model
	 * @throws CreateModelException
	 */
	public <T> T createModel( T referenceModel, boolean withPolicies ) throws CreateModelException {
		
		Erector erector = erectors.get( referenceModel.getClass() );
		
		if ( erector == null ) {
			throw new CreateModelException( "Unregistered class: " + referenceModel.getClass() );
		}
		
		erector.setReference( referenceModel );
		erector.clearCommands();
		
		T createdModel;
		try {
			createdModel = (T)erector.getTemplate().construct( erector.getTarget() );
		} catch (BlueprintTemplateException e) {
			throw new CreateModelException( e );
		}
		
		if ( withPolicies ) {
			List<BlueprintPolicy> blueprintPolicies = this.getBlueprintPolicies().get( erector.getTarget() );
			if ( blueprintPolicies != null ) {
				
				logger.debug( "  Running Blueprint policies" );
				
				for ( BlueprintPolicy policy : blueprintPolicies ) {
					Map<ModelField,Set<Command>> modelFieldCommands = null;
					try {
						logger.info( "    processing {}", policy );
						modelFieldCommands = policy.process( this, erector, createdModel );
						
					} catch (PolicyException e) {
						new CreateModelException(e);
					}
					
					for ( ModelField modelField : modelFieldCommands.keySet() ) {
						erector.addCommands( modelField, modelFieldCommands.get( modelField ) );
					}
				}
			}
		}
		
		for( ModelField modelField : erector.getModelFields() ) {
			
			logger.debug( "ModelField {}", ReflectionToStringBuilder.toString(modelField) );

			Object value = null;
			
			if ( withPolicies ) {
				List<FieldPolicy> fieldPolicies = this.getFieldPolicies().get( modelField.getTarget() );
				if ( fieldPolicies != null ) {
					
					logger.debug( "  Running Field policies" );
					
					for ( FieldPolicy policy : fieldPolicies ) {
						try {
							logger.info( "    processing {} for {}", policy, modelField.getTarget() );
							Command command = policy.process( this, erector, modelField, createdModel );
							if ( command != null ) {
								erector.addCommand( modelField, command );
							}
						} catch (PolicyException e) {
							new CreateModelException(e);
						}
					}
				}
			}
			
			logger.debug( "  ModelField commands: {}", erector.getCommands(modelField));
			
			if ( !erector.getCommands( modelField ).contains( Command.SKIP_INJECTION ) ) {
				
				// Process DefaultField
				if ( modelField instanceof DefaultField ) {
					
					DefaultField defaultField = (DefaultField)modelField;
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_REFERENCE_INJECTION ) ) {
						try {
							value = erector.getTemplate().get( referenceModel, defaultField.getName() );
						} catch (BlueprintTemplateException e) {
							throw new CreateModelException( e );
						} 
					}
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_BLUEPRINT_INJECTION ) ) {
						// Use value set in the model, otherwise use value set in blueprint
						if ( value == null ) {
							value = defaultField.getValue();
						}
					}
					
					// If value is an instance of FieldCallBack, eval the callback and use the value
					if ( value != null & value instanceof FieldCallBack ) {
						FieldCallBack callBack = (FieldCallBack)value;
						value = callBack.get( referenceModel );
					}
					
					try {
						createdModel = erector.getTemplate().set( createdModel, defaultField.getName(), value );
					} catch (BlueprintTemplateException e) {
						throw new CreateModelException( e );
					}
					
				// Process MappedField
			    } else if ( modelField instanceof MappedField ) {
					
					MappedField mappedField = (MappedField)modelField;
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_REFERENCE_INJECTION ) ) {
						try {
							value = erector.getTemplate().get( referenceModel, mappedField.getName() );
						} catch (BlueprintTemplateException e) {
							throw new CreateModelException( e );
						}
					}
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_BLUEPRINT_INJECTION ) ) {
						if ( value == null   ) {
							if ( !mappedField.isNullable() ) {
								value = this.createModel( mappedField.getTarget() );
							}
						} 
					}
					
					try {
						createdModel = erector.getTemplate().set( createdModel, mappedField.getName(), value );
					} catch (BlueprintTemplateException e) {
						throw new CreateModelException( e );
					}
					
				// Process MappedListField
				} else if ( modelField instanceof MappedListField ) {
					
					MappedListField listField = (MappedListField)modelField;
					
					List modelList = null;
					try {
						value =  ( List )erector.getTemplate().construct( listField.getTargetList() );
					} catch (BlueprintTemplateException e) {
						throw new CreateModelException( e );
					}
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_INJECTION ) ) {
						try {
							modelList = (List)erector.getTemplate().get( referenceModel, listField.getName() );
						} catch (BlueprintTemplateException e) {
							throw new CreateModelException( e );
						}
					}
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_BLUEPRINT_INJECTION ) ) {
						if ( modelList == null ) {
							for ( int x = 0; x < listField.getSize(); x ++ ) {
								((List)value).add( this.createModel( listField.getTarget() ) );
							}
						} else {
							for ( Object object : modelList ) {
								((List)value).add( this.createModel( object ) );
							}
						}
					}
					
					try {
						createdModel = erector.getTemplate().set( createdModel, listField.getName(), value );
					} catch (BlueprintTemplateException e) {
						throw new CreateModelException( e );
					}
					
			    // Process MappedSetField
				} else if ( modelField instanceof MappedSetField ) {
					
					MappedSetField setField = (MappedSetField)modelField;
					
					Set modelSet = null;
					try {
						value = erector.getTemplate().construct( setField.getTargetSet() );
					} catch (BlueprintTemplateException e) {
						throw new CreateModelException( e );
					}
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_INJECTION ) ) {
						try {
							modelSet = (Set)erector.getTemplate().get( referenceModel, setField.getName() );
						} catch (BlueprintTemplateException e) {
							throw new CreateModelException( e );
						}
					}
					
					if ( !erector.getCommands( modelField ).contains( Command.SKIP_BLUEPRINT_INJECTION ) ) {
						if ( modelSet == null ) {
							for ( int x = 0; x < setField.getSize(); x ++ ) {
								((Set)value).add( this.createModel( setField.getTarget() ) );
							}
						} else {
							for ( Object object : modelSet ) {
								((Set)value).add( this.createModel( object ) );
							}
						}
					}
					
					try {
						createdModel = erector.getTemplate().set( createdModel, setField.getName(), value );
					} catch (BlueprintTemplateException e) {
						throw new CreateModelException( e );
					}
				}
			} 
		}
		
		return createdModel;
	}
	
	/**
	 * Registered Blueprints
	 * 
	 * @return {@link List<Blueprint>}
	 */
	public List<Object> getBlueprints() {
		return blueprints;
	}
	
	/**
	 * Map of Class to their {@link Erector}.
	 * 
	 * @return {@link Map<Class, Erector>}
	 */
	public Map<Class,Erector> getErectors() {
		return erectors;
	}
	
	public Map<Class,List<BlueprintPolicy>> getBlueprintPolicies() {
		return blueprintPolicies;
	}
	
	public Map<Class,List<FieldPolicy>> getFieldPolicies() {
		return fieldPolicies;
	}
}
