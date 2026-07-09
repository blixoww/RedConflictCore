package fr.redconflict.essentials.service;

import fr.redconflict.essentials.model.SeenRecord;
import fr.redconflict.essentials.repository.SeenRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Traces de connexion : alimente /seen et sert de référentiel nom → UUID
 * pour les commandes acceptant des joueurs hors ligne (/pay, /eco give...).
 */
public class SeenService {

    private final SeenRepository repository;

    public SeenService(SeenRepository repository) {
        this.repository = repository;
    }

    public void recordJoin(Player player) {
        repository.recordJoin(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }

    public void recordQuit(Player player) {
        repository.recordQuit(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Recherche par nom, insensible à la casse. @return null si totalement inconnu. */
    public SeenRecord findByName(String name) {
        return repository.findByName(name);
    }

    /**
     * Résout un nom en UUID : joueur en ligne d'abord (nom exact),
     * sinon dernier passage connu en base. @return null si inconnu.
     */
    public UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        SeenRecord record = repository.findByName(name);
        return record != null ? record.getUuid() : null;
    }
}
