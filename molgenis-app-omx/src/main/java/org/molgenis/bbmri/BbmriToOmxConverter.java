package org.molgenis.bbmri;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.molgenis.MolgenisFieldTypes.FieldTypeEnum;
import org.molgenis.data.Entity;
import org.molgenis.data.Repository;
import org.molgenis.data.RepositoryCollection;
import org.molgenis.data.Writable;
import org.molgenis.data.WritableFactory;
import org.molgenis.data.excel.ExcelRepositoryCollection;
import org.molgenis.data.excel.ExcelWriter;
import org.molgenis.data.processor.TrimProcessor;
import org.molgenis.data.support.MapEntity;
import org.molgenis.model.elements.Dataset;
import org.molgenis.omx.auth.Institute;
import org.molgenis.omx.auth.Person;
import org.molgenis.omx.auth.PersonRole;
import org.molgenis.omx.observ.DataSet;
import org.molgenis.omx.observ.ObservableFeature;
import org.molgenis.omx.observ.Protocol;
import org.molgenis.omx.observ.target.OntologyTerm;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

public class BbmriToOmxConverter
{

	/**
	 * @throws InvalidFormatException
	 * @throws IOException
	 * @throws FileNotFoundException
	 * @param args
	 * @throws
	 */
	public static void main(String[] args) throws IOException, InvalidFormatException
	{
		if (args.length != 2)
		{
			System.err.println("usage: java " + BbmriToOmxConverter.class.getSimpleName() + " inputfolder outputfile");
			return;
		}

		File file = new File(args[1]);
		if (file.exists()) file.delete();

		new BbmriToOmxConverter(args[0], args[1]).convert();
	}

	/**
	 * TODO CURRENT_N should be int? TODO GWA_DATA_N should be int? TODO discuss what to do with canRead, canWrite,
	 * owns, Acronym, Approved
	 */
	private enum FeatureDescription
	{
		COHORT("Cohort", FieldTypeEnum.STRING), CATEGORY("Category", FieldTypeEnum.XREF), SUBCATEGORY("Subcategory",
				FieldTypeEnum.XREF), TOPIC("Topic", FieldTypeEnum.MREF), COORDINATOR("Coordinator", FieldTypeEnum.MREF), INSTITUTION(
				"Institution", FieldTypeEnum.MREF), CURRENT_N("Current n=", FieldTypeEnum.STRING), BIODATA("Biodata",
				FieldTypeEnum.MREF), GWA_DATA_N("GWA data n=", FieldTypeEnum.STRING), GWA_PLATFORM("GWA platform",
				FieldTypeEnum.TEXT), GWA_COMMENTS("GWA comments", FieldTypeEnum.TEXT), GENERAL_COMMENTS(
				"General comments", FieldTypeEnum.TEXT), PUBLICATIONS("Publications", FieldTypeEnum.TEXT);

		private final String name;
		private final FieldTypeEnum type;
		private final String identifier;

		private FeatureDescription(String name, FieldTypeEnum type)
		{
			this.name = name;
			this.type = type;
			this.identifier = UUID.randomUUID().toString();
		}

		public String getName()
		{
			return name;
		}

		public FieldTypeEnum getType()
		{
			return type;
		}

		public String getIdentifier()
		{
			return identifier;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	private final File inputFolder;
	private final File outputFile;

	public BbmriToOmxConverter(String inputFolder, String outputFile)
	{
		if (inputFolder == null) throw new IllegalArgumentException("inputFolder is null");
		if (outputFile == null) throw new IllegalArgumentException("outputFile is null");
		this.inputFolder = new File(inputFolder);
		this.outputFile = new File(outputFile);
		if (!this.inputFolder.isDirectory())
		{
			throw new IllegalArgumentException("inputfolder is not a directory [" + inputFolder + "]");
		}
	}

	public void convert() throws IOException, InvalidFormatException
	{
		Map<String, File> entityFileMap = getEntityFileMap(inputFolder);

		WritableFactory outputWriter = new ExcelWriter(outputFile);
		try
		{
			String dataSetIdentifier = "biobank";
			String protocolIdentifier = UUID.randomUUID().toString();

			writeFeatures(outputWriter);
			writeProtocol(outputWriter, protocolIdentifier);
			writeDataSet(outputWriter, dataSetIdentifier, protocolIdentifier);
			Map<String, String> personRoleMap = writePersonRoles(outputWriter, entityFileMap);
			Map<String, String> ontologyMap = writeOntologyTerms(outputWriter, entityFileMap);
			Map<String, String> instituteMap = writeInstitutes(outputWriter, entityFileMap);
			Map<String, String> personMap = writePersons(outputWriter, entityFileMap, personRoleMap);
			writeDataSetMatrix(outputWriter, dataSetIdentifier, entityFileMap, ontologyMap, personMap, instituteMap);
		}
		finally
		{
			IOUtils.closeQuietly(outputWriter);
		}
	}

	private Map<String, String> writeInstitutes(WritableFactory writableFactory, Map<String, File> entityFileMap)
			throws IOException, InvalidFormatException
	{
		Map<String, String> instituteMap = new HashMap<String, String>();

		Writable writable = writableFactory.createWritable(Institute.class.getSimpleName(), Arrays.asList(
				Institute.IDENTIFIER, Institute.NAME, Institute.ADDRESS, Institute.PHONE, Institute.EMAIL,
				Institute.FAX, Institute.TOLLFREEPHONE, Institute.CITY, Institute.COUNTRY));

		RepositoryCollection repositorySource = new ExcelRepositoryCollection(entityFileMap.get("institutes.xls"),
				new TrimProcessor());

		try
		{
			Repository repo = repositorySource.getRepositoryByEntityName("BiobankInstitute");
			try
			{
				for (Entity inputEntity : repo)
				{
					String name = inputEntity.getString("name");
					String identifier = UUID.randomUUID().toString();
					instituteMap.put(name, identifier);

					Entity outputEntity = new MapEntity();
					outputEntity.set(Institute.IDENTIFIER, identifier);
					outputEntity.set(Institute.NAME, name);
					outputEntity.set(Institute.ADDRESS, inputEntity.getString("Address"));
					outputEntity.set(Institute.PHONE, inputEntity.getString("Phone"));
					outputEntity.set(Institute.EMAIL, inputEntity.getString("Email"));
					outputEntity.set(Institute.FAX, inputEntity.getString("Fax"));
					outputEntity.set(Institute.TOLLFREEPHONE, inputEntity.getString("tollFreePhone"));
					outputEntity.set(Institute.CITY, inputEntity.getString("City"));
					outputEntity.set(Institute.COUNTRY, inputEntity.getString("Country"));
					writable.add(outputEntity);
				}
			}
			finally
			{
				repo.close();
			}
		}
		finally
		{
			IOUtils.closeQuietly(writable);
		}

		return instituteMap;
	}

	private Map<String, String> writePersons(WritableFactory outputWriter, Map<String, File> entityFileMap,
			Map<String, String> ontologyMap) throws IOException, InvalidFormatException
	{
		Map<String, String> personMap = new HashMap<String, String>();

		Writable writable = outputWriter.createWritable(Person.class.getSimpleName(), Arrays.asList(Person.IDENTIFIER,
				Person.NAME, Person.ADDRESS, Person.PHONE, Person.EMAIL, Person.FAX, Person.TOLLFREEPHONE, Person.CITY,
				Person.COUNTRY, Person.FIRSTNAME, Person.MIDINITIALS, Person.LASTNAME, Person.TITLE, Person.AFFILIATION
						+ "_" + Institute.NAME, Person.DEPARTMENT, Person.ROLES + "_" + PersonRole.IDENTIFIER));

		RepositoryCollection repositorySource = new ExcelRepositoryCollection(
				entityFileMap.get("biobankcoordinator.xls"), new TrimProcessor());

		try
		{

			Repository repo = repositorySource.getRepositoryByEntityName("BiobankCoordinator");
			try
			{
				for (Entity entity : repo)
				{
					String name = entity.getString("name");
					String identifier = UUID.randomUUID().toString();
					personMap.put(name, identifier);

					Entity outputEntity = new MapEntity();
					outputEntity.set(Person.IDENTIFIER, identifier);
					outputEntity.set(Person.NAME, name);
					outputEntity.set(Person.ADDRESS, entity.getString("Address"));
					outputEntity.set(Person.PHONE, entity.getString("Phone"));
					outputEntity.set(Person.EMAIL, entity.getString("Email"));
					outputEntity.set(Person.FAX, entity.getString("Fax"));
					outputEntity.set(Person.TOLLFREEPHONE, entity.getString("tollFreePhone"));
					outputEntity.set(Person.CITY, entity.getString("City"));
					outputEntity.set(Person.COUNTRY, entity.getString("Country"));
					outputEntity.set(Person.FIRSTNAME, entity.getString("FirstName"));
					outputEntity.set(Person.MIDINITIALS, entity.getString("MidInitials"));
					outputEntity.set(Person.LASTNAME, entity.getString("LastName"));
					outputEntity.set(Person.TITLE, entity.getString("Title"));
					outputEntity.set(Person.AFFILIATION + "_" + Institute.NAME, entity.getString("Affiliation_name"));
					outputEntity.set(Person.DEPARTMENT, entity.getString("Department"));
					outputEntity.set(Person.ROLES + "_" + PersonRole.IDENTIFIER,
							ontologyMap.get(entity.getString("Roles_name")));
					writable.add(outputEntity);
				}
			}
			finally
			{
				repo.close();
			}
		}
		finally
		{
			IOUtils.closeQuietly(writable);
		}

		return personMap;
	}

	private Map<String, String> writePersonRoles(WritableFactory writableFactory, Map<String, File> entityFileMap)
			throws IOException, InvalidFormatException
	{
		Map<String, String> personRoleMap = new HashMap<String, String>();

		Writable writable = writableFactory.createWritable(PersonRole.class.getSimpleName(),
				Arrays.asList(PersonRole.IDENTIFIER, PersonRole.NAME));
		try
		{
			RepositoryCollection repositorySource = new ExcelRepositoryCollection(entityFileMap.get("personrole.xls"),
					new TrimProcessor());

			Repository repo = repositorySource.getRepositoryByEntityName("BiobankPersonRole");
			try
			{

				for (Entity inputEntity : repo)
				{
					String name = inputEntity.getString("name");
					String identifier = UUID.randomUUID().toString();
					personRoleMap.put(name, identifier);

					Entity outputEntity = new MapEntity();
					outputEntity.set(OntologyTerm.IDENTIFIER, identifier);
					outputEntity.set(OntologyTerm.NAME, name);
					writable.add(outputEntity);
				}
			}
			finally
			{
				repo.close();
			}

		}
		finally
		{
			IOUtils.closeQuietly(writable);
		}
		return personRoleMap;
	}

	private Map<String, String> writeOntologyTerms(WritableFactory writableFactory, Map<String, File> entityFileMap)
			throws IOException, InvalidFormatException
	{
		Map<String, String> ontologyMap = new HashMap<String, String>();

		Writable writable = writableFactory.createWritable(OntologyTerm.class.getSimpleName(),
				Arrays.asList(OntologyTerm.IDENTIFIER, OntologyTerm.NAME));
		try
		{
			// biodata
			{
				RepositoryCollection repositorySource = new ExcelRepositoryCollection(
						entityFileMap.get("biobankdatatype.xls"), new TrimProcessor());

				Repository repo = repositorySource.getRepositoryByEntityName("BiobankDataType");
				try
				{
					for (Entity entity : repo)
					{
						String name = entity.getString("name");
						String identifier = UUID.randomUUID().toString();
						ontologyMap.put(name, identifier);

						Entity outputEntity = new MapEntity();
						outputEntity.set(OntologyTerm.IDENTIFIER, identifier);
						outputEntity.set(OntologyTerm.NAME, name);
						writable.add(outputEntity);
					}
				}
				finally
				{
					repo.close();
				}

			}

			// category
			{
				RepositoryCollection repositorySource = new ExcelRepositoryCollection(
						entityFileMap.get("categories.xls"), new TrimProcessor());
				Repository repo = repositorySource.getRepositoryByEntityName("BiobankCategory");
				try
				{
					for (Entity entity : repo)
					{
						String name = entity.getString("name");
						String identifier = UUID.randomUUID().toString();
						ontologyMap.put(name, identifier);

						Entity output = new MapEntity();
						output.set(OntologyTerm.IDENTIFIER, identifier);
						output.set(OntologyTerm.NAME, name);
						writable.add(output);
					}
				}
				finally
				{
					repo.close();
				}

			}

			// subcategory
			{
				RepositoryCollection repositorySource = new ExcelRepositoryCollection(
						entityFileMap.get("subcategories.xls"), new TrimProcessor());
				Repository repo = repositorySource.getRepositoryByEntityName("BiobankSubCategory");
				try
				{
					for (Entity entity : repo)
					{
						String name = entity.getString("name");
						String identifier = UUID.randomUUID().toString();
						ontologyMap.put(name, identifier);

						Entity output = new MapEntity();
						output.set(OntologyTerm.IDENTIFIER, identifier);
						output.set(OntologyTerm.NAME, name);
						writable.add(output);
					}
				}
				finally
				{
					repo.close();
				}

			}

			// topics
			{
				RepositoryCollection repositorySource = new ExcelRepositoryCollection(entityFileMap.get("topics.xls"),
						new TrimProcessor());

				Repository repo = repositorySource.getRepositoryByEntityName("BiobankTopic");
				try
				{
					for (Entity entity : repo)
					{
						String name = entity.getString("name");
						String identifier = UUID.randomUUID().toString();
						ontologyMap.put(name, identifier);

						Entity output = new MapEntity();
						output.set(OntologyTerm.IDENTIFIER, identifier);
						output.set(OntologyTerm.NAME, name);
						writable.add(output);
					}
				}
				finally
				{
					repo.close();
				}

			}
		}
		finally
		{
			IOUtils.closeQuietly(writable);
		}
		return ontologyMap;
	}

	private void writeDataSetMatrix(WritableFactory writableFactory, String dataSetIdentifier,
			Map<String, File> entityFileMap, Map<String, String> ontologyMap, final Map<String, String> personMap,
			final Map<String, String> institutionMap) throws IOException, InvalidFormatException
	{

		Writable writable = writableFactory.createWritable(DataSet.class.getSimpleName().toLowerCase() + '_'
				+ dataSetIdentifier,
				Lists.transform(Arrays.asList(FeatureDescription.values()), new Function<FeatureDescription, String>()
				{
					@Override
					public String apply(FeatureDescription featureDescription)
					{
						return featureDescription.getIdentifier();
					}
				}));

		RepositoryCollection repositorySource = new ExcelRepositoryCollection(entityFileMap.get("cohorts.xls"),
				new TrimProcessor());
		try
		{

			Repository repo = repositorySource.getRepositoryByEntityName("Biobank");
			try
			{
				for (Entity inputEntity : repo)
				{
					Entity outputEntity = new MapEntity();
					for (FeatureDescription featureDescription : FeatureDescription.values())
					{
						String featureIdentifier = featureDescription.getIdentifier();
						switch (featureDescription)
						{
							case COHORT:
								outputEntity.set(featureIdentifier, inputEntity.get("Cohort"));
								break;
							case BIODATA:
								outputEntity.set(featureIdentifier, ontologyMap.get(inputEntity.get("Biodata_name")));
								break;
							case CATEGORY:
								outputEntity.set(featureIdentifier, ontologyMap.get(inputEntity.get("Category_name")));
								break;
							case COORDINATOR:
								List<String> coordinatorIdentifiers = Lists.transform(
										inputEntity.getList("Coordinator_name"), new Function<String, String>()
										{
											@Override
											public String apply(String coordinatorName)
											{
												return personMap.get(coordinatorName);
											}
										});
								outputEntity.set(featureIdentifier, coordinatorIdentifiers);
								break;
							case CURRENT_N:
								outputEntity.set(featureIdentifier, inputEntity.get("PanelSize"));
								break;
							case GENERAL_COMMENTS:
								outputEntity.set(featureIdentifier, inputEntity.get("GeneralComments"));
								break;
							case GWA_COMMENTS:
								outputEntity.set(featureIdentifier, inputEntity.get("GwaComments"));
								break;
							case GWA_DATA_N:
								outputEntity.set(featureIdentifier, inputEntity.get("GwaDataNum"));
								break;
							case GWA_PLATFORM:
								outputEntity.set(featureIdentifier, inputEntity.get("GwaPlatform"));
								break;
							case INSTITUTION:
								List<String> institutionIdentifiers = Lists.transform(
										inputEntity.getList("Institutes_name"), new Function<String, String>()
										{
											@Override
											public String apply(String coordinatorName)
											{
												return institutionMap.get(coordinatorName);
											}
										});
								outputEntity.set(featureIdentifier, institutionIdentifiers);
								break;
							case PUBLICATIONS:
								outputEntity.set(featureIdentifier, inputEntity.get("Publications"));
								break;
							case SUBCATEGORY:
								outputEntity.set(featureIdentifier,
										ontologyMap.get(inputEntity.get("SubCategory_name")));
								break;
							case TOPIC:
								outputEntity.set(featureIdentifier, ontologyMap.get(inputEntity.get("Topic_name")));
								break;
							default:
								break;
						}
					}
					writable.add(outputEntity);
				}
			}
			finally
			{
				repo.close();
			}

		}
		finally
		{
			IOUtils.closeQuietly(writable);
		}
	}

	private void writeFeatures(WritableFactory writableFactory) throws IOException
	{
		Writable writable = writableFactory.createWritable(ObservableFeature.class.getSimpleName(),
				Arrays.asList(ObservableFeature.IDENTIFIER, ObservableFeature.NAME, ObservableFeature.DATATYPE));
		try
		{
			for (FeatureDescription featureDescription : FeatureDescription.values())
			{
				Entity entity = new MapEntity();
				entity.set(ObservableFeature.IDENTIFIER, featureDescription.getIdentifier());
				entity.set(ObservableFeature.NAME, featureDescription.getName());
				entity.set(ObservableFeature.DATATYPE, featureDescription.getType().toString().toLowerCase());
				writable.add(entity);
			}
		}
		finally
		{
			writable.close();
		}
	}

	private void writeProtocol(WritableFactory writableFactory, String identifier) throws IOException
	{
		Writable writable = writableFactory.createWritable(
				Protocol.class.getSimpleName(),
				Arrays.asList(Protocol.IDENTIFIER, Protocol.NAME, Protocol.FEATURES + "_"
						+ ObservableFeature.IDENTIFIER));
		try
		{
			String featureIdentifiersStr = Joiner.on(',').join(
					Lists.transform(Arrays.asList(FeatureDescription.values()),
							new Function<FeatureDescription, String>()
							{
								@Override
								public String apply(FeatureDescription featureDescription)
								{
									return featureDescription.getIdentifier();
								}
							}));

			Entity entity = new MapEntity();
			entity.set(Protocol.IDENTIFIER, identifier);
			entity.set(Protocol.NAME, "Biobanks");
			entity.set(Protocol.FEATURES + "_" + ObservableFeature.IDENTIFIER, featureIdentifiersStr);
			writable.add(entity);
		}
		finally
		{
			writable.close();
		}
	}

	private void writeDataSet(WritableFactory writableFactory, String identifier, String protocolIdentifier)
			throws IOException
	{
		Writable writable = writableFactory.createWritable(Dataset.class.getSimpleName(),
				Arrays.asList(DataSet.IDENTIFIER, DataSet.NAME, DataSet.PROTOCOLUSED + "_" + Protocol.IDENTIFIER));
		try
		{
			Entity entity = new MapEntity();
			entity.set(DataSet.IDENTIFIER, identifier);
			entity.set(DataSet.NAME, "Biobanks data set");
			entity.set(DataSet.PROTOCOLUSED + "_" + Protocol.IDENTIFIER, protocolIdentifier);
			writable.add(entity);
		}
		finally
		{
			writable.close();
		}
	}

	private Map<String, File> getEntityFileMap(File folder)
	{
		Map<String, File> fileMap = new HashMap<String, File>();
		File[] files = folder.listFiles();
		for (File file : files)
			fileMap.put(file.getName(), file);
		return fileMap;
	}

}