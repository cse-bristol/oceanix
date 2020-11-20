{config, pkgs, ...} :
with pkgs.lib;
{
  # Defines the options you can use in a network file.
  
  imports = [ ./keys.nix ];
  
  options = {
    deployment.digitalOcean.region = mkOption {
      type = types.str;
      default = "lon1";
      description = "A digitalocean region slug";
    };
    deployment.digitalOcean.size   = mkOption {
      type = types.str;
      default = "s-1vcpu-2gb";
      description = "A digitalocean size slug";
    };
    deployment.digitalOcean.image = mkOption {
      type=types.either types.int types.str;
      description = ''
        Either the name or ID of a digitalocean image.
        The image should be booting a nixos install that you can ssh into.
      '';
    };
    deployment.sshKey = mkOption {
      type = types.str;
      description = ''
        An ssh public key to use for access. Will be enabled for root.
      '';
    };
    deployment.copies = mkOption {
      type = types.int;
      default = 1;
      description = "If more than 1, this machine will be replicated this many times and the name suffixed with the index.";
    };
    deployment.addHost = mkOption {
      type = types.bool;
      default = false;
      description = "If true, this machine's ip will be added to the hosts file of other machines.";
    };
    deployment.activateAfter = mkOption {
      type = types.listOf types.string;
      default = [];
      description = "Names of machines in the network that have to have their new configuration activated before this one.";
    };
  };

  config = {
    users.users.root.openssh.authorizedKeys.keys = [ config.deployment.sshKey ];
  };
}
