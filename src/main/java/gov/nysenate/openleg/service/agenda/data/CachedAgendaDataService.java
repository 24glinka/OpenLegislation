package gov.nysenate.openleg.service.agenda.data;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import gov.nysenate.openleg.dao.agenda.AgendaDao;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.model.agenda.Agenda;
import gov.nysenate.openleg.model.agenda.AgendaId;
import gov.nysenate.openleg.model.agenda.AgendaNotFoundEx;
import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.sobi.SobiFragment;
import gov.nysenate.openleg.model.cache.CacheEvictEvent;
import gov.nysenate.openleg.model.cache.CacheWarmEvent;
import gov.nysenate.openleg.service.base.data.CachingService;
import gov.nysenate.openleg.model.cache.ContentCache;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.MemoryUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.ehcache.EhCacheCache;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.List;

@Service
public class CachedAgendaDataService implements AgendaDataService, CachingService
{
    private static final Logger logger = LoggerFactory.getLogger(CachedAgendaDataService.class);

    /** Agenda cache will store full agendas. */
    @Autowired private CacheManager cacheManager;
    private static final String agendaCacheName = "agendas";
    private EhCacheCache agendaCache;

    /** The maximum heap size (in MB) the agenda cache can consume. */
    @Value("${cache.agenda.heap.size}")
    private long agendaCacheSizeMb;

    /** Backing store for the agenda data. */
    @Autowired private AgendaDao agendaDao;

    /** Used to subscribe and post events. */
    @Autowired private EventBus eventBus;

    @PostConstruct
    private void init() {
        eventBus.register(this);
        setupCaches();
    }

    @PreDestroy
    private void cleanUp() {
        evictCaches();
        cacheManager.removeCache(agendaCacheName);
    }

    /** --- CachingService implementation --- */

    @Override
    public Ehcache getCache() {
        return agendaCache.getNativeCache();
    }

    /** {@inheritDoc} */
    @Override
    public void setupCaches() {
        Cache cache = new Cache(new CacheConfiguration().name(agendaCacheName)
            .eternal(true)
            .maxBytesLocalHeap(agendaCacheSizeMb, MemoryUnit.MEGABYTES)
            .sizeOfPolicy(defaultSizeOfPolicy()));
        cacheManager.addCache(cache);
        this.agendaCache = new EhCacheCache(cache);
    }

    /** {@inheritDoc} */
    @Override
    public void evictCaches() {
        logger.info("Clearing the agenda cache.");
        agendaCache.clear();
    }

    /** {@inheritDoc} */
    @Override
    @Subscribe
    public void handleCacheEvictEvent(CacheEvictEvent evictEvent) {
        if (evictEvent.affects(ContentCache.AGENDA)) {
            evictCaches();
        }
    }

    /**
     * Pre-load the agenda cache by first clearing its current contents and then requesting every agenda
     * in the past 4 years.
     */
    public void warmCaches() {
        evictCaches();
        logger.info("Warming up agenda cache.");
        int year = LocalDate.now().getYear();
        for (int i = 3; i >= 0; i--) {
            logger.info("Fetching agendas for year {}", (year - i));
            getAgendaIds(year - i, SortOrder.ASC).forEach(a -> getAgenda(a));
        }
        logger.info("Done warming up agenda cache.");
    }

    /** {@inheritDoc} */
    @Override
    @Subscribe
    public void handleCacheWarmEvent(CacheWarmEvent warmEvent) {
        if (warmEvent.affects(ContentCache.AGENDA)) {
            warmCaches();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Agenda getAgenda(AgendaId agendaId) throws AgendaNotFoundEx {
        if (agendaId == null) {
            throw new IllegalArgumentException("AgendaId cannot be null.");
        }
        try {
            Agenda agenda = (agendaCache.get(agendaId) != null) ? (Agenda) agendaCache.get(agendaId).get() : null;
            if (agenda == null) {
                logger.debug("Fetching agenda {}", agendaId);
                agenda = agendaDao.getAgenda(agendaId);
                agendaCache.put(agendaId, agenda);
            }
            return agenda;
        }
        catch (EmptyResultDataAccessException ex) {
            throw new AgendaNotFoundEx(agendaId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<AgendaId> getAgendaIds(int year, SortOrder idOrder) {
        return agendaDao.getAgendaIds(year, idOrder);
    }

    /** {@inheritDoc} */
    @Override
    public void saveAgenda(Agenda agenda, SobiFragment sobiFragment) {
        if (agenda == null) {
            throw new IllegalArgumentException("Agenda cannot be null when saving.");
        }
        agendaDao.updateAgenda(agenda, sobiFragment);
        agendaCache.put(agenda.getId(), agenda);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteAgenda(AgendaId agendaId) {
        agendaDao.deleteAgenda(agendaId);
        agendaCache.evict(agendaId);
    }
}