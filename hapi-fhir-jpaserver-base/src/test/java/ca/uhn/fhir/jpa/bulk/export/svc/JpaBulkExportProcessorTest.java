package ca.uhn.fhir.jpa.bulk.export.svc;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.PersistentIdToForcedIdMap;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.bulk.export.model.ExportPIDIteratorParameters;
import ca.uhn.fhir.jpa.dao.IResultIterator;
import ca.uhn.fhir.jpa.dao.ISearchBuilder;
import ca.uhn.fhir.jpa.dao.SearchBuilderFactory;
import ca.uhn.fhir.jpa.dao.mdm.MdmExpansionCacheSvc;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.search.SearchRuntimeDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.mdm.api.MdmMatchResultEnum;
import ca.uhn.fhir.mdm.dao.IMdmLinkDao;
import ca.uhn.fhir.mdm.model.MdmPidTuple;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.server.bulk.BulkDataExportOptions;
import ca.uhn.fhir.rest.api.server.storage.BaseResourcePersistentId;
import ca.uhn.fhir.rest.api.server.storage.IResourcePersistentId;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class JpaBulkExportProcessorTest {

	private class ListResultIterator implements IResultIterator {

		private List<IResourcePersistentId> myList;

		private int index;

		public ListResultIterator(List<IResourcePersistentId> theList) {
			myList = theList;
		}

		@Override
		public int getSkippedCount() {
			return 0;
		}

		@Override
		public int getNonSkippedCount() {
			return 0;
		}

		@Override
		public Collection<IResourcePersistentId> getNextResultBatch(long theBatchSize) {
			return null;
		}

		@Override
		public void close() throws IOException {

		}

		@Override
		public boolean hasNext() {
			return index < myList.size();
		}

		@Override
		public IResourcePersistentId next() {
			return myList.get(index++);
		}
	}

	@Spy
	private FhirContext myFhirContext = FhirContext.forR4Cached();

	@Mock
	private BulkExportHelperService myBulkExportHelperService;

	@Mock
	private JpaStorageSettings myStorageSettings;

	@Mock
	private DaoRegistry myDaoRegistry;

	@Mock
	private SearchBuilderFactory mySearchBuilderFactory;

	@Mock
	private IIdHelperService myIdHelperService;

	@Mock
	private IMdmLinkDao myMdmLinkDao;

	@Mock
	private MdmExpansionCacheSvc myMdmExpansionCacheSvc;

	@InjectMocks
	private JpaBulkExportProcessor myProcessor;

	private ExportPIDIteratorParameters createExportParameters(BulkDataExportOptions.ExportStyle theExportStyle) {
		ExportPIDIteratorParameters parameters = new ExportPIDIteratorParameters();
		parameters.setJobId("jobId");
		parameters.setExportStyle(theExportStyle);
		if (theExportStyle == BulkDataExportOptions.ExportStyle.GROUP) {
			parameters.setGroupId("123");
		}
		parameters.setStartDate(new Date());
		return parameters;
	}

	private List<IPrimitiveType> createPatientTypes() {
		long id1 = 123;
		long id2 = 456;
		String patient1Id = "Patient/" + id1;
		String patient2Id = "Patient/" + id2;

		List<IPrimitiveType> patientTypes = Arrays.asList(
			new IdDt(patient1Id),
			new IdDt(patient2Id)
		);
		return patientTypes;
	}

	private MdmPidTuple createTuple(long theGroupId, long theGoldenId) {
		return MdmPidTuple.fromGoldenAndSource(JpaPid.fromId(theGoldenId), JpaPid.fromId(theGroupId));
	}

	@Test
	public void getResourcePidIterator_paramsWithPatientExportStyle_returnsAnIterator() {
		// setup
		ExportPIDIteratorParameters parameters = createExportParameters(BulkDataExportOptions.ExportStyle.PATIENT);
		parameters.setResourceType("Patient");

		SearchParameterMap map = new SearchParameterMap();
		List<SearchParameterMap> maps = new ArrayList<>();
		maps.add(map);

		JpaPid pid = JpaPid.fromId(123L);
		JpaPid pid2 = JpaPid.fromId(456L);
		ListResultIterator resultIterator = new ListResultIterator(
			Arrays.asList(pid, pid2)
		);

		// extra mocks
		IFhirResourceDao<?> mockDao = mock(IFhirResourceDao.class);
		ISearchBuilder searchBuilder = mock(ISearchBuilder.class);

		// when
		when(myStorageSettings.getIndexMissingFields())
			.thenReturn(JpaStorageSettings.IndexEnabledEnum.ENABLED);
		when(myBulkExportHelperService.createSearchParameterMapsForResourceType(any(RuntimeResourceDefinition.class), eq(parameters), any(boolean.class)))
			.thenReturn(maps);
		// from getSearchBuilderForLocalResourceType
		when(myDaoRegistry.getResourceDao(anyString()))
			.thenReturn(mockDao);
		when(mySearchBuilderFactory.newSearchBuilder(eq(mockDao), eq(parameters.getResourceType()), any()))
			.thenReturn(searchBuilder);
		// ret
		when(searchBuilder.createQuery(
			eq(map),
			any(SearchRuntimeDetails.class),
			any(),
			eq(RequestPartitionId.allPartitions())))
			.thenReturn(resultIterator);

		// test
		Iterator<JpaPid> pidIterator = myProcessor.getResourcePidIterator(parameters);

		// verify
		assertNotNull(pidIterator);
		assertTrue(pidIterator.hasNext());
		assertEquals(pid, pidIterator.next());
		assertTrue(pidIterator.hasNext());
		assertEquals(pid2, pidIterator.next());
		assertFalse(pidIterator.hasNext());
	}

	@Test
	public void getResourcePidIterator_patientStyleWithIndexMissingFieldsDisabled_throws() {
		// setup
		ExportPIDIteratorParameters parameters = createExportParameters(BulkDataExportOptions.ExportStyle.PATIENT);
		parameters.setResourceType("Patient");

		// when
		when(myStorageSettings.getIndexMissingFields())
			.thenReturn(JpaStorageSettings.IndexEnabledEnum.DISABLED);

		// test
		try {
			myProcessor.getResourcePidIterator(parameters);
			fail();
		} catch (IllegalStateException ex) {
			assertTrue(ex.getMessage().contains("You attempted to start a Patient Bulk Export,"));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	public void getResourcePidIterator_groupExportStyleWithPatientResource_returnsIterator(boolean theMdm) {
		// setup
		ExportPIDIteratorParameters parameters = createExportParameters(BulkDataExportOptions.ExportStyle.GROUP);
		parameters.setResourceType("Patient");

		JpaPid groupId = JpaPid.fromId(Long.parseLong(parameters.getGroupId()));
		long groupGoldenPid = 4567l;

		Group groupResource = new Group();
		groupResource.setId(parameters.getGroupId());

		List<IPrimitiveType> patientTypes = createPatientTypes();
		List<IResourcePersistentId> pids = new ArrayList<>();
		for (IPrimitiveType type : patientTypes) {
			pids.add(JpaPid.fromId(((IdDt) type).getIdPartAsLong()));
		}

		MdmPidTuple tuple = createTuple(groupId.getId(), groupGoldenPid);

		IFhirResourceDao<Group> groupDao = mock(IFhirResourceDao.class);
		parameters.setExpandMdm(theMdm); // set mdm expansion

		// extra mocks
		IFhirResourceDao<?> mockDao = mock(IFhirResourceDao.class);
		ISearchBuilder searchBuilder = mock(ISearchBuilder.class);

		// from getMembersFromGroupWithFilter
		when(myBulkExportHelperService.createSearchParameterMapsForResourceType(any(RuntimeResourceDefinition.class), eq(parameters), any(boolean.class)))
			.thenReturn(Collections.singletonList(new SearchParameterMap()));
		// from getSearchBuilderForLocalResourceType
		when(myDaoRegistry.getResourceDao(not(eq("Group"))))
			.thenReturn(mockDao);
		when(mySearchBuilderFactory.newSearchBuilder(eq(mockDao), eq(parameters.getResourceType()), any()))
			.thenReturn(searchBuilder);
		// ret
		when(searchBuilder.createQuery(
			any(SearchParameterMap.class),
			any(SearchRuntimeDetails.class),
			any(),
			eq(RequestPartitionId.allPartitions())))
			.thenReturn(new ListResultIterator(pids));

		// mdm expansion stuff
		if (theMdm) {
			when(myDaoRegistry.getResourceDao(eq("Group")))
				.thenReturn(groupDao);
			when(groupDao.read(eq(new IdDt(parameters.getGroupId())), any(SystemRequestDetails.class)))
				.thenReturn(groupResource);
			when(myIdHelperService.translatePidsToForcedIds(any(Set.class)))
				.thenAnswer(params -> {
					Set<IResourcePersistentId> uniqPids = params.getArgument(0);
					HashMap<IResourcePersistentId, Optional<String>> answer = new HashMap<>();
					for (IResourcePersistentId l : uniqPids) {
						answer.put(l, Optional.empty());
					}
					return new PersistentIdToForcedIdMap(answer);
				});
			when(myIdHelperService.getPidOrNull(any(), any(Group.class)))
				.thenReturn(groupId);
			when(myMdmLinkDao.expandPidsFromGroupPidGivenMatchResult(any(BaseResourcePersistentId.class), eq(MdmMatchResultEnum.MATCH)))
				.thenReturn(Collections.singletonList(tuple));
			when(myMdmExpansionCacheSvc.hasBeenPopulated())
				.thenReturn(false); // does not matter, since if false, it then goes and populates
		}

		// test
		Iterator<JpaPid> pidIterator = myProcessor.getResourcePidIterator(parameters);

		// verify
		assertNotNull(pidIterator);
		int count = 0;
		assertTrue(pidIterator.hasNext());
		while (pidIterator.hasNext()) {
			JpaPid pid = pidIterator.next();
			long idAsLong = pid.getId();
			boolean existing = pids.contains(JpaPid.fromId(idAsLong));
			if (!existing) {
				assertTrue(theMdm);
				assertEquals(groupGoldenPid, idAsLong);
			} else {
				count++;
			}
		}
		int total = pids.size();
		assertEquals(total, count);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	public void getResourcePidIterator_groupExportNonPatient_returnsIterator(boolean theMdm) {
		// setup
		ExportPIDIteratorParameters parameters = createExportParameters(BulkDataExportOptions.ExportStyle.GROUP);
		parameters.setResourceType("Observation");

		JpaPid groupId = JpaPid.fromId(Long.parseLong(parameters.getGroupId()));
		Group groupResource = new Group();
		groupResource.setId(parameters.getGroupId());
		long groupGoldenPid = 4567l;

		JpaPid pid = JpaPid.fromId(123L);
		JpaPid pid2 = JpaPid.fromId(456L);
		ListResultIterator resultIterator = new ListResultIterator(
			Arrays.asList(pid, pid2)
		);

		MdmPidTuple tuple = createTuple(groupId.getId(), groupGoldenPid);
		List<IPrimitiveType> patientTypes = createPatientTypes();

		IFhirResourceDao<Group> groupDao = mock(IFhirResourceDao.class);
		IFhirResourceDao<Observation> observationDao = mock(IFhirResourceDao.class);
		parameters.setExpandMdm(theMdm); // set mdm expansion

		// extra mocks
		IFhirResourceDao<?> mockDao = mock(IFhirResourceDao.class);
		ISearchBuilder searchBuilder = mock(ISearchBuilder.class);

		// when
		when(myDaoRegistry.getResourceDao(eq("Group")))
			.thenReturn(groupDao);
		when(groupDao.read(any(IIdType.class), any(SystemRequestDetails.class)))
			.thenReturn(groupResource);
		when(myIdHelperService.getPidOrNull(any(), eq(groupResource)))
			.thenReturn(groupId);
		when(myBulkExportHelperService.createSearchParameterMapsForResourceType(any(RuntimeResourceDefinition.class), eq(parameters), any(boolean.class)))
			.thenReturn(Collections.singletonList(new SearchParameterMap()));
		when(myDaoRegistry.getResourceDao(not(eq("Group"))))
			.thenReturn(mockDao);
		when(mySearchBuilderFactory.newSearchBuilder(eq(mockDao), not(eq("Group")), any()))
			.thenReturn(searchBuilder);

		// ret
		when(searchBuilder.createQuery(
			any(SearchParameterMap.class),
			any(SearchRuntimeDetails.class),
			any(),
			eq(RequestPartitionId.allPartitions())))
			.thenReturn(new ListResultIterator(Collections.singletonList(pid)))
			.thenReturn(resultIterator);

		if (theMdm) {
			when(myMdmLinkDao.expandPidsFromGroupPidGivenMatchResult(any(BaseResourcePersistentId.class), eq(MdmMatchResultEnum.MATCH)))
				.thenReturn(Collections.singletonList(tuple));
			when(myIdHelperService.translatePidsToForcedIds(any(Set.class)))
				.thenAnswer(params -> {
					Set<IResourcePersistentId> uniqPids = params.getArgument(0);
					HashMap<IResourcePersistentId, Optional<String>> answer = new HashMap<>();
					for (IResourcePersistentId l : uniqPids) {
						answer.put(l, Optional.empty());
					}
					return new PersistentIdToForcedIdMap(answer);
				});
		}

		// test
		Iterator<JpaPid> pidIterator = myProcessor.getResourcePidIterator(parameters);

		// verify
		assertNotNull(pidIterator, "PID iterator null for mdm = " + theMdm);
		assertTrue(pidIterator.hasNext(), "PID iterator empty for mdm = " + theMdm);
	}

	@Test
	public void getResourcePidIterator_systemExport_returnsIterator() {
		// setup
		ExportPIDIteratorParameters parameters = createExportParameters(BulkDataExportOptions.ExportStyle.SYSTEM);
		parameters.setResourceType("Patient");

		JpaPid pid = JpaPid.fromId(123L);
		JpaPid pid2 = JpaPid.fromId(456L);
		ListResultIterator resultIterator = new ListResultIterator(
			Arrays.asList(pid, pid2)
		);

		// extra mocks
		IFhirResourceDao<Patient> dao = mock(IFhirResourceDao.class);
		ISearchBuilder searchBuilder = mock(ISearchBuilder.class);

		// when
		when(myBulkExportHelperService.createSearchParameterMapsForResourceType(
			any(RuntimeResourceDefinition.class),
			any(ExportPIDIteratorParameters.class),
			any(boolean.class)
		)).thenReturn(Collections.singletonList(new SearchParameterMap()));
		when(myDaoRegistry.getResourceDao(eq("Patient")))
			.thenReturn(dao);
		when(mySearchBuilderFactory.newSearchBuilder(
			any(IFhirResourceDao.class),
			anyString(),
			any()
		)).thenReturn(searchBuilder);
		when(searchBuilder.createQuery(
			any(SearchParameterMap.class),
			any(SearchRuntimeDetails.class),
			any(),
			eq(RequestPartitionId.allPartitions())
		)).thenReturn(resultIterator);

		// test
		Iterator<JpaPid> iterator = myProcessor.getResourcePidIterator(parameters);

		// verify
		assertNotNull(iterator);
		assertTrue(iterator.hasNext());
		int count = 0;
		while (iterator.hasNext()) {
			IResourcePersistentId ret = iterator.next();
			assertTrue(
				ret.equals(pid) || ret.equals(pid2)
			);
			count++;
		}
		assertEquals(2, count);
	}
}
